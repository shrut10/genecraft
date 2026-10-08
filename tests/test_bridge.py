import json
import unittest

from bridge.genecraft_bridge import extract_action, function_tools, parse_sse_response, visible_models


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

    def test_only_exposes_six_bounded_game_actions(self):
        self.assertEqual(function_tools()[0]["type"], "namespace")
        self.assertEqual(function_tools()[0]["name"], "minecraft")
        names = {tool["name"] for tool in function_tools()[0]["tools"]}
        self.assertEqual(names, {"speak", "follow_player", "go_to_waypoint", "stop_moving", "remember", "message_agent"})

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
