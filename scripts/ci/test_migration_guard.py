"""已发布迁移守卫的反例测试：在临时 git 仓库里跑真实脚本。"""
import os, shutil, subprocess, tempfile, unittest

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ZERO = "0" * 40


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
        self.write("db/RELEASED.sha256", out)
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
        r = self.resolve(EVENT_NAME="push", REF_NAME="feat", PUSH_BEFORE=self.main_sha)
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
        with open(os.path.join(self.repo, "db/RELEASED.sha256"), "w") as f:
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
        with open(os.path.join(self.repo, "db/RELEASED.sha256"), "w") as f:
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

    def test_dispatch_uses_merge_base_and_passes_clean(self):
        sh(self.repo, "git", "checkout", "-qb", "feat3")
        self.write("db/migration/V2__add.sql", "select 1;\n")
        self.commit("add V2")
        r = self.resolve(EVENT_NAME="workflow_dispatch", REF_NAME="feat3")
        self.assertEqual(r.stdout.strip(), self.main_sha, r.stderr)
        self.assertEqual(self.guard(r.stdout.strip()).returncode, 0)


if __name__ == "__main__":
    unittest.main()
