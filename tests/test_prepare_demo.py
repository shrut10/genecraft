import importlib.util
import io
import tempfile
import unittest
import zipfile
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    "prepare_demo", Path(__file__).resolve().parents[1] / "scripts" / "prepare-demo.py")
demo = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(demo)


def make_jar(path: Path, version: str, name: str = "GeneCraft") -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w") as jar:
        jar.writestr("plugin.yml", f"name: {name}\nversion: {version}\n")
    return path


class DemoInstallerTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.server = self.root / "server"
        self.source = make_jar(self.root / "target" / f"genecraft-paper-{demo.VERSION}.jar", demo.VERSION)

    def test_artifact_and_plugin_version_match_project(self):
        self.assertEqual(demo.PLUGIN_JAR.name, f"genecraft-paper-{demo.project_version()}.jar")
        metadata = (demo.ROOT / "src/main/resources/plugin.yml").read_text()
        self.assertIn(f"version: {demo.VERSION}\n", metadata)

    def test_version_is_direct_project_version_not_dependency_version(self):
        (self.root / "pom.xml").write_text(
            '<project xmlns="http://maven.apache.org/POM/4.0.0">'
            '<parent><version>99.0.0</version></parent><version>1.2.3-SNAPSHOT</version></project>')
        self.assertEqual(demo.project_version(self.root), "1.2.3-SNAPSHOT")

    def test_upgrade_backs_up_old_jars_without_touching_other_plugins(self):
        plugins = self.server / "plugins"
        old = make_jar(plugins / "genecraft-paper-0.3.2.jar", "0.3.2")
        old_bytes = old.read_bytes()
        other = make_jar(plugins / "other.jar", "1.0.0", "Other")
        renamed = make_jar(plugins / "genecraft-paper-unrelated.jar", "1.0.0", "Other")
        with redirect_stdout(io.StringIO()):
            installed = demo.install_plugin(self.source, self.server)
        self.assertEqual(installed.read_bytes(), self.source.read_bytes())
        self.assertFalse(old.exists())
        self.assertTrue(other.exists())
        self.assertTrue(renamed.exists())
        archived = list((self.server / "backups").glob("*/genecraft-paper-0.3.2.jar"))
        self.assertEqual(len(archived), 1)
        self.assertEqual(archived[0].read_bytes(), old_bytes)

    def test_same_version_reinstall_backs_up_the_previous_artifact(self):
        destination = make_jar(self.server / "plugins" / self.source.name, demo.VERSION)
        with zipfile.ZipFile(destination, "a") as jar:
            jar.writestr("old-content.txt", "preserve me")
        previous = destination.read_bytes()
        with redirect_stdout(io.StringIO()):
            demo.install_plugin(self.source, self.server)
        archived = list((self.server / "backups").glob(f"*/{self.source.name}"))
        self.assertEqual(archived[0].read_bytes(), previous)
        self.assertEqual(destination.read_bytes(), self.source.read_bytes())

    def test_rejects_stale_metadata_before_mutating_install(self):
        make_jar(self.source, "0.2.1")
        with self.assertRaisesRegex(RuntimeError, "metadata"):
            demo.install_plugin(self.source, self.server)
        self.assertFalse(self.server.exists())

    def test_never_overwrites_an_unrelated_plugin_with_matching_filename(self):
        destination = make_jar(self.server / "plugins" / self.source.name, "9.9.9", "Other")
        contents = destination.read_bytes()
        with self.assertRaisesRegex(RuntimeError, "unrecognised"):
            demo.install_plugin(self.source, self.server)
        self.assertEqual(destination.read_bytes(), contents)

    def test_failed_install_restores_previous_plugin(self):
        old = make_jar(self.server / "plugins" / "genecraft-paper-0.3.2.jar", "0.3.2")
        previous = old.read_bytes()
        original_replace = Path.replace

        def fail_new_file(path, target):
            if path.name == "new-plugin.tmp":
                raise OSError("simulated disk failure")
            return original_replace(path, target)

        with patch.object(Path, "replace", fail_new_file):
            with self.assertRaisesRegex(OSError, "disk failure"):
                demo.install_plugin(self.source, self.server)
        self.assertEqual(old.read_bytes(), previous)

    def test_existing_paper_needs_no_network_request(self):
        self.server.mkdir()
        (self.server / "paper.jar").write_bytes(b"existing server")
        with patch.object(demo, "SERVER_DIR", self.server), patch.object(demo, "fetch_json") as fetch:
            with redirect_stdout(io.StringIO()):
                build, jar = demo.download_paper()
        fetch.assert_not_called()
        self.assertEqual(build, "existing")
        self.assertEqual(jar.read_bytes(), b"existing server")

    def test_setup_preserves_world_config_and_accepted_eula(self):
        self.server.mkdir()
        files = {
            "world/level.dat": b"existing world",
            "plugins/GeneCraft/config.yml": b"saved goals and supplies",
            "server.properties": b"custom port and settings",
            "eula.txt": b"eula=true\n",
            "paper.jar": b"existing server",
        }
        for filename, contents in files.items():
            path = self.server / filename
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(contents)
        with patch.object(demo, "SERVER_DIR", self.server), patch.object(demo, "DEMO_HOME", self.root), \
                patch.object(demo, "PLUGIN_JAR", self.source), redirect_stdout(io.StringIO()):
            demo.main()
        for filename, contents in files.items():
            self.assertEqual((self.server / filename).read_bytes(), contents)


if __name__ == "__main__":
    unittest.main()
