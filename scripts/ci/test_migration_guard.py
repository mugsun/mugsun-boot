"""已发布迁移守卫的反例测试：在临时 git 仓库里跑真实脚本。"""
import os, re, shutil, subprocess, tempfile, unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ZERO = "0" * 40
# 清单路径以守卫脚本里的 MANIFEST= 为准，换清单时这里不用跟着改
with open(os.path.join(ROOT, "scripts", "check-released-migrations.sh"), encoding="utf-8") as _f:
    MANIFEST = re.search(r"^MANIFEST=(\S+)", _f.read(), re.M).group(1)


def sh(cwd, *args, env=None, check=True):
    return subprocess.run(args, cwd=cwd, env=env, check=check, text=True, capture_output=True)


class GuardTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.origin = os.path.join(self.tmp, "origin.git")
        self.repo = os.path.join(self.tmp, "repo")
        sh(self.tmp, "git", "init", "-q", "--bare", "-b", "main", self.origin)
        sh(self.tmp, "git", "clone", "-q", self.origin, self.repo)
        for k, v in (("user.name", "t"), ("user.email", "t@t"), ("commit.gpgsign", "false")):
            sh(self.repo, "git", "config", k, v)
        os.makedirs(os.path.join(self.repo, "scripts", "ci"))
        os.makedirs(os.path.join(self.repo, "db", "migration"))
        for f in ("scripts/check-released-migrations.sh", "scripts/ci/resolve-migration-base.sh"):
            shutil.copy(os.path.join(ROOT, f), os.path.join(self.repo, f))
        self.mig = "db/migration/V1__init.sql"
        self.write(self.mig, "create table t(id int);\n")
        out = sh(self.repo, "shasum", "-a", "256", self.mig).stdout
        os.makedirs(os.path.dirname(os.path.join(self.repo, MANIFEST)), exist_ok=True)
        self.write(MANIFEST, out)
        self.commit("init")
        sh(self.repo, "git", "push", "-q", "origin", "main")
        self.main_sha = self.head()

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def write(self, rel, text):
        with open(os.path.join(self.repo, rel), "w") as f:
            f.write(text)

    def commit(self, msg):
        sh(self.repo, "git", "add", "-A")
        sh(self.repo, "git", "commit", "-qm", msg)

    def head(self):
        return sh(self.repo, "git", "rev-parse", "HEAD").stdout.strip()

    def resolve(self, **env):
        e = dict(os.environ, **env)
        return sh(self.repo, "scripts/ci/resolve-migration-base.sh", env=e, check=False)

    def guard(self, base):
        e = dict(os.environ)
        e.pop("BASE_SHA", None)
        if base is not None:
            e["BASE_SHA"] = base
        return sh(self.repo, "scripts/check-released-migrations.sh", env=e, check=False)

    def test_modified_released_migration_fails(self):
        sh(self.repo, "git", "checkout", "-qb", "feat")
        self.write(self.mig, "create table t(id bigint);\n")
        self.commit("edit released")
        r = self.resolve(EVENT_NAME="push", REF_NAME="feat")
        self.assertEqual(r.returncode, 0, r.stderr)
        g = self.guard(r.stdout.strip())
        self.assertNotEqual(g.returncode, 0)
        self.assertIn("已发布迁移被修改", g.stdout)

    def test_main_push_zero_before_fails(self):
        r = self.resolve(EVENT_NAME="push", REF_NAME="main", PUSH_BEFORE=ZERO)
        self.assertNotEqual(r.returncode, 0)
        self.assertEqual(r.stdout.strip(), "")

    def test_main_push_unreachable_before_fails(self):
        r = self.resolve(EVENT_NAME="push", REF_NAME="main", PUSH_BEFORE="1" * 40)
        self.assertNotEqual(r.returncode, 0)

    def test_empty_base_fails(self):
        self.assertNotEqual(self.guard(None).returncode, 0)
        self.assertNotEqual(self.guard("").returncode, 0)

    def test_pr_without_base_fails(self):
        self.assertNotEqual(self.resolve(EVENT_NAME="pull_request", PR_BASE_SHA="").returncode, 0)

    def test_branch_zero_before_falls_back_to_merge_base(self):
        sh(self.repo, "git", "checkout", "-qb", "feat2")
        with open(os.path.join(self.repo, MANIFEST), "w") as f:
            f.write("# truncated\n")
        self.commit("drop manifest line")
        r = self.resolve(EVENT_NAME="push", REF_NAME="feat2", PUSH_BEFORE=ZERO)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(r.stdout.strip(), self.main_sha)
        g = self.guard(r.stdout.strip())
        self.assertNotEqual(g.returncode, 0)
        self.assertIn("只能追加", g.stdout)

    def test_manifest_line_modified_fails(self):
        sh(self.repo, "git", "checkout", "-qb", "feat4")
        with open(os.path.join(self.repo, MANIFEST), "w") as f:
            f.write("f" * 64 + "  " + self.mig + "\n")
        self.commit("rewrite manifest line")
        r = self.resolve(EVENT_NAME="push", REF_NAME="feat4", PUSH_BEFORE=self.main_sha)
        g = self.guard(r.stdout.strip())
        self.assertNotEqual(g.returncode, 0)
        self.assertIn("只能追加", g.stdout)

    def test_no_base_emits_error_annotation(self):
        r = self.resolve(EVENT_NAME="push", REF_NAME="main", PUSH_BEFORE="")
        self.assertIn("::error::", r.stderr)
        self.assertIn("::error::", self.guard("").stdout)

    def test_pr_valid_base_passes(self):
        sh(self.repo, "git", "checkout", "-qb", "pr-ok")
        self.write("db/migration/V2__add.sql", "select 1;\n")
        self.commit("add V2")
        r = self.resolve(EVENT_NAME="pull_request", PR_BASE_SHA=self.main_sha)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(r.stdout.strip(), self.main_sha)
        self.assertEqual(self.guard(r.stdout.strip()).returncode, 0)

    def test_pr_manifest_edit_fails(self):
        sh(self.repo, "git", "checkout", "-qb", "pr-bad")
        with open(os.path.join(self.repo, MANIFEST), "w") as f:
            f.write("e" * 64 + "  " + self.mig + "\n")
        self.commit("edit manifest")
        r = self.resolve(EVENT_NAME="pull_request", PR_BASE_SHA=self.main_sha)
        self.assertEqual(r.returncode, 0, r.stderr)
        g = self.guard(r.stdout.strip())
        self.assertNotEqual(g.returncode, 0)
        self.assertIn("只能追加", g.stdout)

    def test_dispatch_uses_merge_base_and_passes_clean(self):
        sh(self.repo, "git", "checkout", "-qb", "feat3")
        self.write("db/migration/V2__add.sql", "select 1;\n")
        self.commit("add V2")
        r = self.resolve(EVENT_NAME="workflow_dispatch", REF_NAME="feat3")
        self.assertEqual(r.stdout.strip(), self.main_sha, r.stderr)
        self.assertEqual(self.guard(r.stdout.strip()).returncode, 0)

    def test_manifest_new_since_base_passes(self):
        """换发布线时清单是新文件：基线提交里没有它，只会出现新增行，不算删改。"""
        sh(self.repo, "git", "checkout", "-qb", "new-line")
        with open(os.path.join(self.repo, MANIFEST)) as f:
            lines = f.read()
        os.remove(os.path.join(self.repo, MANIFEST))
        self.commit("base without manifest")
        base = self.head()
        self.write(MANIFEST, lines)
        self.commit("add manifest")
        g = self.guard(base)
        self.assertEqual(g.returncode, 0, g.stdout + g.stderr)
        # 登记进新清单以后照样受保护
        self.write(self.mig, "create table t(id bigint);\n")
        self.commit("edit released")
        g = self.guard(base)
        self.assertNotEqual(g.returncode, 0)
        self.assertIn("已发布迁移被修改", g.stdout)

    def test_comment_only_manifest_allows_baseline_edits(self):
        """版本还没发布：清单只有注释，基线可以直接改。"""
        sh(self.repo, "git", "checkout", "-qb", "unreleased")
        os.remove(os.path.join(self.repo, MANIFEST))
        self.commit("base without manifest")
        base = self.head()
        self.write(MANIFEST, "# 还没有发布过迁移\n")
        self.write(self.mig, "create table t(id bigint);\n")
        self.commit("empty manifest, edit baseline")
        g = self.guard(base)
        self.assertEqual(g.returncode, 0, g.stdout + g.stderr)
        self.assertIn("(0 files)", g.stdout)

    def test_deleting_manifest_fails(self):
        sh(self.repo, "git", "checkout", "-qb", "no-manifest")
        os.remove(os.path.join(self.repo, MANIFEST))
        self.commit("delete manifest")
        self.assertNotEqual(self.guard(self.main_sha).returncode, 0)


if __name__ == "__main__":
    unittest.main()
