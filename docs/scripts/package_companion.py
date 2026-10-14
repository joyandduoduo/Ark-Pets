"""Build a standalone Windows companion, without changing an installed ArkPets."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--java-home", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
repo = Path(__file__).resolve().parents[2]
output = args.output.resolve()
java = args.java_home / "bin/java.exe"
subprocess.run([str(java), f"-Dorg.gradle.java.home={args.java_home}", "-classpath", "gradle/wrapper/gradle-wrapper.jar", "org.gradle.wrapper.GradleWrapperMain", "desktop:distJar", "desktop:jlink", "--no-daemon"], cwd=repo, check=True)
jar = next((repo / "desktop/build/dist").glob("ArkPets-*.jar"))
output.mkdir(parents=True, exist_ok=True)
shutil.copy2(jar, output / "ArkPets-Companion.jar")
shutil.copytree(repo / "desktop/build/jlink/runtime", output / "runtime", dirs_exist_ok=True)
shutil.copy2(repo / "LICENSE", output / "LICENSE")
launcher = '@echo off\r\nchcp 65001 >nul\r\ncd /d "%~dp0"\r\nstart "" "%~dp0runtime\\bin\\javaw.exe" -Dfile.encoding=UTF-8 -cp "%~dp0ArkPets-Companion.jar" cn.harryh.arkpets.CompanionEntry "--catalog=%~dp0companion-data\\catalog.json"\r\n'
(output / "打开角色陪伴.cmd").write_text(launcher, encoding="utf-8")
receipt = {"jar_sha256": hashlib.sha256((output / "ArkPets-Companion.jar").read_bytes()).hexdigest(), "source_commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip(), "source_dirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=repo, text=True).strip()), "entrypoint": "cn.harryh.arkpets.CompanionEntry", "installed_application_modified": False}
(output / "build-receipt.json").write_text(json.dumps(receipt, indent=2), encoding="utf-8")
print(output / "打开角色陪伴.cmd")
