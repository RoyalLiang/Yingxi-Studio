import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from release_automation import ensure_tag, git, plan
from release_notes import load_notes, render_notes
from versioning import validate
from release_tools import publish
from types import SimpleNamespace


def notes(version="1.2.3-alpha.1"):
    return (f"# 映汐 {version} / Yingxi {version}\n\n## 中文\n\n【拾荒者信笺】\n\n"
            "### 更新内容\n\n- 修好放映站的播放恢复，让离开的影片能顺着原来的路回来。\n\n"
            "## English\n\n[Scavenger's letter]\n\n### Changes\n\n"
            "- Repair playback restoration at the projection station so unfinished films can find their way back.\n")


class ReleaseNotesTest(unittest.TestCase):
    def test_change_only_notes_do_not_publish_audit_or_commit_footers(self):
        version=validate({"versionName":"1.2.3-alpha.1","versionCode":9})
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);(root/'releases').mkdir()
            text="# 映汐播放器 1.2.3-alpha.1 / Yingxi 1.2.3-alpha.1\n\n## 中文\n\n拾荒者信笺：\n- 拆掉首页重复路牌。\n\n## English\n\nScavenger letter:\n- Clear the duplicate signposts from Home.\n"
            (root/'releases/v1.2.3-alpha.1.md').write_text(text)
            body=render_notes(root,version,'a'*40)
            self.assertTrue(body.startswith('## 中文'))
            self.assertNotIn('Source commit',body)
            self.assertNotIn('Validation',body)
            self.assertIn('duplicate signposts',body)

    def test_bilingual_content_and_exact_version_are_required(self):
        version = validate({"versionName": "1.2.3-alpha.1", "versionCode": 9})
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "releases").mkdir()
            path = root / "releases" / f'{version["tag"]}.md'
            path.write_text(notes(), encoding="utf-8")
            self.assertNotIn("Source commit", render_notes(root, version, "abc123"))
            for invalid in (notes().split("## English")[0], notes("1.2.4"),
                            notes().replace("- Repair playback restoration at the projection station so unfinished films can find their way back.", ""),
                            notes().replace("- 修好放映站的播放恢复，让离开的影片能顺着原来的路回来。", "- TODO"),
                            notes().replace("### Changes", "### Other")):
                with self.subTest(invalid=invalid):
                    path.write_text(invalid, encoding="utf-8")
                    with self.assertRaises(ValueError):
                        load_notes(root, version)


class ReleasePlanTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.call("init", "-q", "-b", "main")
        self.call("config", "user.name", "Release Fixture")
        self.call("config", "user.email", "fixture@example.invalid")
        (self.root / "version.json").write_text(json.dumps({"versionName": "1.2.3-alpha.1", "versionCode": 9}))
        (self.root / "releases").mkdir()
        (self.root / "releases/v1.2.3-alpha.1.md").write_text(notes(), encoding="utf-8")
        self.call("add", ".")
        self.call("commit", "-qm", "Fixture")
        self.call("update-ref", "refs/remotes/origin/main", "HEAD")
        self.tag = "v1.2.3-alpha.1"

    def call(self, *args):
        return git(self.root, *args)

    def test_main_candidate_is_planned_without_creating_a_tag(self):
        candidate = plan(self.root, allow_create=True)
        self.assertEqual(candidate, {"tag": self.tag, "ref": self.call("rev-parse", "HEAD"), "create_tag": "true"})
        self.assertEqual(self.call("tag"), "")
        with self.assertRaises(ValueError):
            plan(self.root)

    def test_existing_tag_can_be_retried_but_never_moved(self):
        self.call("tag", "-a", self.tag, "-m", "Fixture release")
        self.assertEqual(plan(self.root, self.tag)["create_tag"], "false")
        with patch("release_automation.subprocess.check_output", wraps=subprocess.check_output) as output:
            ensure_tag(self.root, self.tag)
            self.assertFalse(any(call.args[0][0] == "gh" for call in output.call_args_list))
        self.call("commit", "--allow-empty", "-qm", "New source")
        self.call("update-ref", "refs/remotes/origin/main", "HEAD")
        with self.assertRaisesRegex(ValueError, "another commit"):
            plan(self.root, self.tag, allow_create=True)

    def test_wrong_version_and_source_outside_main_are_rejected(self):
        with self.assertRaisesRegex(ValueError, "match version"):
            plan(self.root, "v9.9.9", allow_create=True)
        self.call("commit", "--allow-empty", "-qm", "Unmerged source")
        with self.assertRaises(subprocess.CalledProcessError):
            plan(self.root, allow_create=True)

    def test_automatic_tag_requires_main_push_and_exact_source_sha(self):
        with patch.dict(os.environ, {"GITHUB_EVENT_NAME": "workflow_dispatch", "GITHUB_REF": "refs/heads/main"}):
            with self.assertRaisesRegex(ValueError, "main push"):
                ensure_tag(self.root, self.tag)
        with patch.dict(os.environ, {"GITHUB_EVENT_NAME": "push", "GITHUB_REF": "refs/heads/main", "GITHUB_SHA": "wrong"}):
            with self.assertRaisesRegex(ValueError, "triggering commit"):
                ensure_tag(self.root, self.tag)

    def test_automatic_tag_is_created_at_the_verified_commit(self):
        commit = self.call("rev-parse", "HEAD")
        original_output, original_run = subprocess.check_output, subprocess.run
        calls = []

        def output(command, **kwargs):
            if command[0] == "gh":
                calls.append(command)
                self.call("tag", self.tag)
                return json.dumps({"ref": f"refs/tags/{self.tag}", "object": {"sha": commit}})
            return original_output(command, **kwargs)

        def run(command, **kwargs):
            if "fetch" in command:
                return subprocess.CompletedProcess(command, 0)
            return original_run(command, **kwargs)

        with patch.dict(os.environ, {"GITHUB_EVENT_NAME": "push", "GITHUB_REF": "refs/heads/main",
                                    "GITHUB_SHA": commit, "GITHUB_REPOSITORY": "owner/repo"}), \
                patch("release_automation.subprocess.check_output", side_effect=output), \
                patch("release_automation.subprocess.run", side_effect=run):
            ensure_tag(self.root, self.tag)
        self.assertEqual(len(calls), 1)
        self.assertIn(f"sha={commit}", calls[0])
        self.assertEqual(plan(self.root)["create_tag"], "false")


class ReleasePublicationTest(unittest.TestCase):
    def test_source_archive_is_uploaded_alongside_the_installer(self):
        metadata = {**validate({"versionName": "1.2.3-alpha.1", "versionCode": 9}),
                    "sourceCommit": "a" * 40, "fileName": "yingxi-1.2.3-alpha.1-9.apk",
                    "sourceFileName": "source-code-1.2.3-alpha.1.zip"}
        with tempfile.TemporaryDirectory() as folder, patch.dict(os.environ, {"GITHUB_REPOSITORY": "owner/repo"}), \
             patch("release_tools.verify_assets", return_value=metadata), patch("release_tools.run", return_value='[[]]'), \
             patch("release_tools.render_notes", return_value=notes()), patch("release_tools.subprocess.run") as command:
            publish(SimpleNamespace(output=Path(folder), tag=metadata["tag"]))
            uploaded = [str(a) for c in command.call_args_list if c.args[0][1:3] == ['release', 'upload'] for a in c.args[0]]
            self.assertIn(str(Path(folder)/metadata['sourceFileName']), uploaded)

    def test_new_and_resumed_drafts_publish_the_same_bilingual_details(self):
        metadata = {**validate({"versionName": "1.2.3-alpha.1", "versionCode": 9}),
                    "sourceCommit": "a" * 40,"fileName":"yingxi-1.2.3-alpha.1-9.apk"}
        draft = {"tag_name": metadata["tag"], "draft": True, "assets": [],
                 "target_commitish": metadata["sourceCommit"]}
        for existing in ([], [draft]):
            with self.subTest(existing=bool(existing)), tempfile.TemporaryDirectory() as folder:
                seen = []

                def publish_command(command, **kwargs):
                    if "--notes-file" in command:
                        body = Path(command[command.index("--notes-file") + 1]).read_text(encoding="utf-8")
                        self.assertIn("## 中文", body)
                        self.assertIn("## English", body)
                        seen.append(command[2])
                    return subprocess.CompletedProcess(command, 0)

                with patch.dict(os.environ, {"GITHUB_REPOSITORY": "owner/repo"}), \
                        patch("release_tools.verify_assets", return_value=metadata), \
                        patch("release_tools.run", return_value=json.dumps([existing])), \
                        patch("release_tools.render_notes", return_value=notes()), \
                        patch("release_tools.subprocess.run", side_effect=publish_command):
                    publish(SimpleNamespace(output=Path(folder), tag=metadata["tag"]))
                self.assertEqual(seen, ["edit"] if existing else ["create", "edit"])

    def test_ios_release_does_not_enter_android_upgrade_history(self):
        metadata={**validate({"versionName":"1.2.3-alpha.1","versionCode":9}),"sourceCommit":"a"*40,"fileName":"yingxi-1.2.3-alpha.1-9.apk"}
        ios={"tag_name":"ios-v0.1.0-alpha.1","draft":False,"assets":[{"name":"yingxi-ios-release.json"}]}
        with tempfile.TemporaryDirectory() as folder, patch.dict(os.environ,{"GITHUB_REPOSITORY":"owner/repo"}), \
             patch("release_tools.verify_assets",return_value=metadata), \
             patch("release_tools.run",return_value=json.dumps([[ios]])), \
             patch("release_tools.render_notes",return_value=notes()), \
             patch("release_tools.subprocess.run") as command:
            publish(SimpleNamespace(output=Path(folder),tag=metadata["tag"]))
            self.assertFalse(any(c.args[0][1:3]==["release","download"] for c in command.call_args_list))

    def test_resumed_draft_with_extra_attachments_is_not_published(self):
        metadata={"tag":"v1.2.3","sourceCommit":"a"*40,"fileName":"yingxi-1.2.3-9.apk"}
        draft={"tag_name":metadata["tag"],"draft":True,"target_commitish":metadata["sourceCommit"],"assets":[{"name":"audit.json"}]}
        with patch.dict(os.environ,{"GITHUB_REPOSITORY":"owner/repo"}),patch("release_tools.verify_assets",return_value=metadata),patch("release_tools.run",return_value=json.dumps([[draft]])),patch("release_tools.subprocess.run") as command:
            with self.assertRaisesRegex(ValueError,"extra assets"):
                publish(SimpleNamespace(output=Path("unused"),tag=metadata["tag"]))
            command.assert_not_called()

    def test_android_history_still_requires_verified_apk_evidence(self):
        metadata={"tag":"v1.2.3","sourceCommit":"a"*40,"fileName":"fixture.apk"}
        previous={"id":1,"tag_name":"v1.2.2","draft":False,"assets":[]}
        with patch.dict(os.environ,{"GITHUB_REPOSITORY":"owner/repo"}), patch("release_tools.verify_assets",return_value=metadata), \
             patch("release_tools.run",return_value=json.dumps([[previous]])), \
             patch("release_tools.previous_from_apk",side_effect=ValueError("Historical APK evidence missing")), patch("release_tools.subprocess.run") as command:
            with self.assertRaisesRegex(ValueError,"Historical APK evidence"):
                publish(SimpleNamespace(output=Path("unused"),tag=metadata["tag"]))
            command.assert_not_called()

    def test_published_version_is_never_replaced(self):
        metadata = {"tag": "v1.2.3", "sourceCommit": "a" * 40,"fileName":"yingxi-1.2.3-alpha.1-9.apk"}
        with patch.dict(os.environ, {"GITHUB_REPOSITORY": "owner/repo"}), \
                patch("release_tools.verify_assets", return_value=metadata), \
                patch("release_tools.run", return_value=json.dumps([[{"tag_name": metadata["tag"], "draft": False}]])), \
                patch("release_tools.subprocess.run") as command:
            with self.assertRaisesRegex(ValueError, "never replaced"):
                publish(SimpleNamespace(output=Path("unused"), tag=metadata["tag"]))
            command.assert_not_called()


if __name__ == "__main__":
    unittest.main()
