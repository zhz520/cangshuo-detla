"""Create a private, first-install BaoTa upload bundle on drive D:."""

from pathlib import Path
import secrets
import shutil
import string
import zipfile

import bcrypt


def main():
    template = Path(__file__).resolve().parent
    project = template.parent.parent
    output = (project / ".local-deploy").resolve()
    if output.drive.upper() != "D:":
        raise SystemExit("Output must be on drive D:.")
    if output.exists():
        raise SystemExit("Private output already exists; refusing to replace credentials.")

    root_password = secrets.token_hex(20)
    db_password = secrets.token_hex(20)
    redis_password = secrets.token_hex(20)
    jwt_secret = secrets.token_hex(32)
    aes_key = secrets.token_hex(8)
    identity_pepper = secrets.token_hex(32)
    identity_encryption_key = secrets.token_hex(32)
    alphabet = string.ascii_letters + string.digits
    admin_password = "".join(secrets.choice(alphabet) for _ in range(24))
    password_hash = bcrypt.hashpw(
        admin_password.encode("ascii"), bcrypt.gensalt(rounds=10, prefix=b"2a")
    ).decode("ascii")
    assert bcrypt.checkpw(admin_password.encode("ascii"), password_hash.encode("ascii"))
    assert len(aes_key.encode("utf-8")) == 16

    bundle = output / "delta"
    initdb = bundle / "initdb"
    initdb.mkdir(parents=True)
    for name in ("docker-compose.yml", "nginx.conf"):
        shutil.copyfile(template / name, bundle / name)
    shutil.copyfile(project / "delta_game.sql", initdb / "01-schema.sql")

    env = (template / ".env.example").read_text(encoding="utf-8")
    replacements = {
        "REPLACE_WITH_RANDOM_ROOT_PASSWORD": root_password,
        "REPLACE_WITH_RANDOM_DB_PASSWORD": db_password,
        "REPLACE_WITH_RANDOM_REDIS_PASSWORD": redis_password,
        "REPLACE_WITH_RANDOM_SECRET_AT_LEAST_32_ASCII_CHARACTERS": jwt_secret,
        "REPLACE_16_CHARS!": aes_key,
        "REPLACE_WITH_RANDOM_IDENTITY_HASH_PEPPER": identity_pepper,
        "REPLACE_WITH_RANDOM_IDENTITY_ENCRYPTION_KEY": identity_encryption_key,
    }
    for old, new in replacements.items():
        env = env.replace(old, new)
    env = env.replace(
        "# Template only. The generated deployment ZIP contains an actual random .env.",
        "# Private installation settings. Do not publish or commit this file.",
    )
    (bundle / ".env").write_text(env, encoding="utf-8", newline="\n")
    (initdb / "02-admin.sql").write_text(
        "-- First-install administrator; does not overwrite an existing account.\n"
        "SET NAMES utf8mb4;\n"
        "INSERT INTO `admin` (`username`, `password`, `nickname`, `role`, `status`, `deleted`)\n"
        f"SELECT 'admin', '{password_hash}', '管理员', 'ADMIN', 1, 0\n"
        "WHERE NOT EXISTS (SELECT 1 FROM `admin` WHERE `username` = 'admin');\n",
        encoding="utf-8", newline="\n",
    )
    shutil.copyfile(template / "宝塔操作说明.md", output / "宝塔操作说明.md")
    (output / "登录信息.txt").write_text(
        "本次首次安装信息；请妥善保管，不要公开。\n\n"
        "管理后台：通过已绑定并启用 HTTPS 的域名访问\n"
        "H5用户端：通过已绑定并启用 HTTPS 的域名访问\n"
        "管理员账号：admin\n"
        f"管理员初始密码：{admin_password}\n"
        "H5手机号登录需先配置并验收阿里云短信验证码服务。\n"
        "数据库与Redis密码见上传包内 delta/.env；不是宝塔已有数据库密码。\n"
        "原README中的UIUIUIUI属于演示环境，本次安装不使用该密码。\n"
        "ZIP仅用于空数据目录的首次安装，不要覆盖已有生产数据库。\n",
        encoding="utf-8", newline="\n",
    )

    archive = output / "delta-baota.zip"
    with zipfile.ZipFile(archive, "x", zipfile.ZIP_DEFLATED) as zf:
        for path in sorted(bundle.rglob("*")):
            if path.is_file():
                zf.write(path, path.relative_to(output).as_posix())
    with zipfile.ZipFile(archive) as zf:
        assert zf.testzip() is None
        assert set(zf.namelist()) == {
            "delta/docker-compose.yml", "delta/nginx.conf", "delta/.env",
            "delta/initdb/01-schema.sql", "delta/initdb/02-admin.sql",
        }
    print(f"Created {archive} ({archive.stat().st_size} bytes).")
    print("Private credentials written to the adjacent login information file.")


if __name__ == "__main__":
    main()
