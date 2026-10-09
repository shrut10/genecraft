import json
import unittest
from unittest.mock import patch

from bridge.genecraft_bridge import (
    explicit_wood_gather_target,
    extract_action,
    function_tools,
    make_plan,
    parse_sse_response,
    should_search_web,
    visible_models,
)


class ResponsesStreamTests(unittest.TestCase):
    def test_reads_permitted_function_call_from_completed_response(self):
        events = [
            'data: {"type":"response.output_item.done","item":{"type":"function_call","name":"speak"}}',
            'data: ' + json.dumps({
                "type": "response.completed",
                "response": {"output": [{
                    "type": "function_call",
                    "namespace": "minecraft",
                    "name": "speak",
                    "arguments": '{"text":"Hello, explorer!"}',
                }]},
            }),
        ]
        response = parse_sse_response(events)
        self.assertEqual(extract_action(response), {"action": "speak", "arguments": {"text": "Hello, explorer!"}})

    def test_keeps_completed_stream_items_when_chatgpt_completion_has_empty_output(self):
        events = [
            'data: ' + json.dumps({"type": "response.output_item.done", "item": {
                "type": "function_call", "namespace": "minecraft", "name": "speak", "arguments": '{"text":"Ready."}',
            }}),
            'data: ' + json.dumps({"type": "response.completed", "response": {"output": []}}),
        ]
        response = parse_sse_response(events)
        self.assertEqual(extract_action(response), {"action": "speak", "arguments": {"text": "Ready."}})

    def test_turns_a_plain_assistant_answer_into_safe_speech(self):
        response = {"output": [{
            "type": "message", "role": "assistant",
            "content": [{"type": "output_text", "text": "I can speak, follow you, or visit a saved waypoint."}],
        }]}
        self.assertEqual(extract_action(response), {
            "action": "speak",
            "arguments": {"text": "I can speak, follow you, or visit a saved waypoint."},
        })

    def test_fallback_speech_is_single_line_bounded_and_strips_minecraft_formatting(self):
        response = {"output": [{
            "type": "message", "role": "assistant",
            "content": [{"type": "output_text", "text": "§c" + ("friendly answer " * 20)}],
        }]}
        action = extract_action(response)
        speech = action["arguments"]["text"]
        self.assertLessEqual(len(speech), 160)
        self.assertNotIn("§", speech)
        self.assertNotIn("\n", speech)

    def test_rejects_a_function_outside_the_game_allowlist(self):
        response = {"output": [{"type": "function_call", "namespace": "minecraft", "name": "run_shell", "arguments": "{}"}]}
        with self.assertRaisesRegex(RuntimeError, "unsupported action"):
            extract_action(response)

    def test_rejects_a_function_from_an_unrecognized_namespace(self):
        response = {"output": [{"type": "function_call", "namespace": "shell", "name": "speak", "arguments": "{}"}]}
        with self.assertRaisesRegex(RuntimeError, "unrecognized action namespace"):
            extract_action(response)

    def test_rejects_speech_with_unexpected_fields(self):
        response = {"output": [{
            "type": "function_call", "namespace": "minecraft", "name": "speak",
            "arguments": '{"text":"hello","command":"/op me"}',
        }]}
        with self.assertRaisesRegex(RuntimeError, "invalid speech text"):
            extract_action(response)

    def test_rejects_waypoint_names_outside_the_saved_name_format(self):
        response = {"output": [{
            "type": "function_call", "namespace": "minecraft", "name": "go_to_waypoint",
            "arguments": '{"name":"../../home"}',
        }]}
        with self.assertRaisesRegex(RuntimeError, "invalid waypoint name"):
            extract_action(response)

    def test_rejects_arguments_for_argument_free_actions(self):
        response = {"output": [{
            "type": "function_call", "namespace": "minecraft", "name": "stop_moving",
            "arguments": '{"target":"other-agent"}',
        }]}
        with self.assertRaisesRegex(RuntimeError, "unexpected action arguments"):
            extract_action(response)

    def test_exposes_only_the_bounded_friend_and_work_order_actions(self):
        self.assertEqual(function_tools()[0]["type"], "namespace")
        self.assertEqual(function_tools()[0]["name"], "minecraft")
        names = {tool["name"] for tool in function_tools()[0]["tools"]}
        self.assertEqual(names, {
            "speak", "follow_player", "go_to_waypoint", "stop_moving", "remember", "message_agent",
            "start_gather_wood", "start_strip_mine", "start_house_build", "cancel_job",
        })

    def test_web_search_is_only_enabled_for_tutorial_or_web_lookup_requests(self):
        self.assertTrue(should_search_web("Build me a house based on a tutorial from the web"))
        self.assertTrue(should_search_web("look up an online guide for a starter home"))
        self.assertFalse(should_search_web("Collect 20 nearby logs"))

    def test_clear_wood_orders_get_a_bounded_target(self):
        self.assertEqual(explicit_wood_gather_target(
            "gather 32 nearby tree logs for builder's house and save the plans in our shared supplies"
        ), 32)
        self.assertEqual(explicit_wood_gather_target("gather a stack of wood pls"), 64)
        self.assertEqual(explicit_wood_gather_target("Could you please get some logs?"), 8)
        self.assertEqual(explicit_wood_gather_target("gather wood"), 16)
        self.assertIsNone(explicit_wood_gather_target("Explain how I could gather wood"))

    def test_model_cannot_turn_an_explicit_wood_order_into_a_spoken_promise(self):
        response = {"output": [{
            "type": "function_call", "namespace": "minecraft", "name": "speak",
            "arguments": '{"text":"On it. I will collect 32 logs."}',
        }]}
        with patch("bridge.genecraft_bridge.active_record", return_value={}), \
             patch("bridge.genecraft_bridge.list_models", return_value=[{"slug": "test-model"}]), \
             patch("bridge.genecraft_bridge.choose_model", return_value="test-model"), \
             patch("bridge.genecraft_bridge.request_response", return_value=response):
            result = make_plan({
                "agent": "Woodcutter",
                "prompt": "gather 32 nearby tree logs for builder's house",
                "goal": "",
                "context": {},
            })
        self.assertEqual(result, {
            "action": "start_gather_wood", "arguments": {"target_logs": 32},
        })

    def test_active_wood_order_is_not_duplicated_by_the_fallback(self):
        response = {"output": [{
            "type": "function_call", "namespace": "minecraft", "name": "speak",
            "arguments": '{"text":"I am already gathering."}',
        }]}
        with patch("bridge.genecraft_bridge.active_record", return_value={}), \
             patch("bridge.genecraft_bridge.list_models", return_value=[{"slug": "test-model"}]), \
             patch("bridge.genecraft_bridge.choose_model", return_value="test-model"), \
             patch("bridge.genecraft_bridge.request_response", return_value=response):
            result = make_plan({
                "agent": "Woodcutter",
                "prompt": "gather 32 nearby tree logs",
                "goal": "",
                "context": {"current_job": {"kind": "gather_wood", "status": "running", "target": 8}},
            })
        self.assertEqual(result["action"], "speak")

    def test_tutorial_orders_search_first_then_request_a_game_action(self):
        research = {"output": [
            {"type": "message", "role": "assistant", "content": [{"type": "output_text", "text": "A square floor, framed walls, windows, and a pitched roof."}]},
            {"type": "web_search_call", "action": {"sources": [{"url": "https://example.org/house", "title": "Starter house tutorial"}]}},
        ]}
        action = {"output": [{
            "type": "function_call", "namespace": "minecraft", "name": "start_gather_wood",
            "arguments": '{"target_logs":24}',
        }]}
        with patch("bridge.genecraft_bridge.active_record", return_value={}), \
             patch("bridge.genecraft_bridge.list_models", return_value=[{"slug": "test-model"}]), \
             patch("bridge.genecraft_bridge.choose_model", return_value="test-model"), \
             patch("bridge.genecraft_bridge.request_response", side_effect=[research, action]) as request:
            result = make_plan({"agent": "Builder", "prompt": "Build from an online tutorial", "goal": "", "context": {}})
        self.assertEqual(result["action"], "start_gather_wood")
        self.assertEqual(result["sources"][0]["url"], "https://example.org/house")
        self.assertEqual(request.call_count, 2)
        self.assertEqual(request.call_args_list[0].args[3], [{"type": "web_search"}])
        self.assertEqual(request.call_args_list[1].args[3][0]["type"], "namespace")
        self.assertIn("tutorial_research", json.loads(request.call_args_list[1].args[2]))

    def test_agent_message_is_bounded_and_rejects_malformed_recipient(self):
        response = {"output": [{
            "type": "function_call", "namespace": "minecraft", "name": "message_agent",
            "arguments": '{"recipient":"Gen_2","text":"The way is clear."}',
        }]}
        self.assertEqual(extract_action(response), {
            "action": "message_agent", "arguments": {"recipient": "Gen_2", "text": "The way is clear."},
        })
        response["output"][0]["arguments"] = '{"recipient":"../../world","text":"hello"}'
        with self.assertRaisesRegex(RuntimeError, "invalid agent message"):
            extract_action(response)

    def test_memory_note_is_bounded(self):
        response = {"output": [{
            "type": "function_call", "namespace": "minecraft", "name": "remember",
            "arguments": '{"note":"The lookout is beside the lake."}',
        }]}
        self.assertEqual(extract_action(response), {
            "action": "remember", "arguments": {"note": "The lookout is beside the lake."},
        })
        response["output"][0]["arguments"] = json.dumps({"note": "x" * 161})
        with self.assertRaisesRegex(RuntimeError, "invalid memory note"):
            extract_action(response)

    def test_accepts_bounded_wood_gathering_job(self):
        response = {"output": [{
            "type": "function_call", "namespace": "minecraft", "name": "start_gather_wood",
            "arguments": '{"target_logs":24}',
        }]}
        self.assertEqual(extract_action(response), {
            "action": "start_gather_wood", "arguments": {"target_logs": 24},
        })
        response["output"][0]["arguments"] = '{"target_logs":1000}'
        with self.assertRaisesRegex(RuntimeError, "invalid wood-gathering target"):
            extract_action(response)

    def test_rejects_unbounded_or_invalid_mining_jobs(self):
        response = {"output": [{
            "type": "function_call", "namespace": "minecraft", "name": "start_strip_mine",
            "arguments": '{"length":32,"y_level":12,"direction":"player_facing"}',
        }]}
        self.assertEqual(extract_action(response), {
            "action": "start_strip_mine", "arguments": {"length": 32, "y_level": 12, "direction": "player_facing"},
        })
        response["output"][0]["arguments"] = '{"length":33,"y_level":12,"direction":"player_facing"}'
        with self.assertRaisesRegex(RuntimeError, "invalid strip-mine"):
            extract_action(response)

    def test_house_blueprints_are_bounded_and_reject_duplicate_or_unsafe_cells(self):
        cells = self.starter_house_blueprint()
        response = {"output": [{
            "type": "function_call", "namespace": "minecraft", "name": "start_house_build",
            "arguments": json.dumps({"blueprint": cells}),
        }]}
        duplicate = list(cells)
        duplicate[1] = dict(duplicate[0])
        response["output"][0]["arguments"] = json.dumps({"blueprint": duplicate})
        with self.assertRaisesRegex(RuntimeError, "duplicate house coordinates"):
            extract_action(response)
        response["output"][0]["arguments"] = json.dumps({"blueprint": cells})
        self.assertEqual(extract_action(response)["action"], "start_house_build")
        cells[1]["material"] = "COMMAND_BLOCK"
        response["output"][0]["arguments"] = json.dumps({"blueprint": cells})
        with self.assertRaisesRegex(RuntimeError, "unsafe or unsupported house block"):
            extract_action(response)

    def starter_house_blueprint(self):
        cells = []
        for x in range(4):
            for z in range(4):
                cells.append({"x": x, "y": 0, "z": z, "material": "OAK_PLANKS"})
                cells.append({"x": x, "y": 3, "z": z, "material": "OAK_PLANKS"})
        for y in (1, 2):
            for x in range(4):
                for z in range(4):
                    if x not in (0, 3) and z not in (0, 3):
                        continue
                    if (x, z) == (1, 0):
                        if y == 1:
                            cells.append({"x": x, "y": y, "z": z, "material": "OAK_DOOR"})
                        continue
                    cells.append({"x": x, "y": y, "z": z, "material": "OAK_PLANKS"})
        return cells

    def test_attaches_https_tutorial_citations_to_work_order(self):
        response = {"output": [
            {"type": "message", "role": "assistant", "content": [{"type": "output_text", "text": "I found a design.",
                "annotations": [{"type": "url_citation", "url_citation": {"url": "https://example.org/house", "title": "Starter house"}}]}]},
            {"type": "function_call", "namespace": "minecraft", "name": "start_gather_wood", "arguments": '{"target_logs":1}'},
        ]}
        self.assertEqual(extract_action(response)["sources"], [{"url": "https://example.org/house", "title": "Starter house"}])

    def test_attaches_citations_from_included_web_search_sources(self):
        response = {"output": [
            {"type": "web_search_call", "action": {"sources": [
                {"url": "https://example.org/design", "title": "Design guide"},
                {"url": "http://unsafe.example/", "title": "Not HTTPS"},
            ]}},
            {"type": "function_call", "namespace": "minecraft", "name": "start_gather_wood", "arguments": '{"target_logs":1}'},
        ]}
        self.assertEqual(extract_action(response)["sources"], [{"url": "https://example.org/design", "title": "Design guide"}])

    def test_requires_stream_completion(self):
        with self.assertRaisesRegex(RuntimeError, "response.completed"):
            parse_sse_response(['data: {"type":"response.created"}'])

    def test_model_picker_uses_only_visible_account_slugs_and_preserves_order(self):
        catalog = {"models": [
            {"slug": "gpt-current", "display_name": "GPT Current", "visibility": "list"},
            {"slug": "internal-preview", "display_name": "Internal", "visibility": "hidden"},
            {"slug": "gpt-fast", "display_name": "GPT Fast", "visibility": "list"},
        ]}
        self.assertEqual(visible_models(catalog), [
            {"slug": "gpt-current", "display_name": "GPT Current"},
            {"slug": "gpt-fast", "display_name": "GPT Fast"},
        ])


if __name__ == "__main__":
    unittest.main()
