import tempfile
import unittest
from pathlib import Path
from release_notes import load_notes
from versioning import validate

class ReleaseVoiceTest(unittest.TestCase):
    def root(self, chinese, english):
        temp=tempfile.TemporaryDirectory();self.addCleanup(temp.cleanup)
        root=Path(temp.name);(root/"releases").mkdir()
        (root/"releases/v0.2.0-alpha.15.md").write_text("# 映汐播放器 0.2.0-alpha.15 / Yingxi 0.2.0-alpha.15\n\n## 中文\n\n"+chinese+"\n\n## English\n\n"+english+"\n")
        return root
    def test_letter_prose_is_accepted(self):
        root=self.root("【拾荒者信笺】\n放映站收到了新信件。自动扫描现在拥有独立入口，沿路带回 SMB、SFTP 和 WebDAV 的候选。", "[Scavenger's letter]\nA new letter reaches the projection station. Automatic discovery now has its own entrance.")
        load_notes(root,validate({"versionName":"0.2.0-alpha.15","versionCode":17}))
    def test_new_letters_cannot_publish_validation_sections(self):
        root=self.root("### 更新内容\n- 自动扫描移到独立入口。\n### 验证与限制\n- 模拟器通过。", "### Changes\n- Give discovery its own entrance.\n### Validation and limitations\n- Emulator checks passed.")
        with self.assertRaises(ValueError):
            load_notes(root,validate({"versionName":"0.2.0-alpha.15","versionCode":17}))

if __name__=="__main__":unittest.main()
