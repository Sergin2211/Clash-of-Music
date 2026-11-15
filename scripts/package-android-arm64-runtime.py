"""Build the Android Python launcher and replace the native payloads of the wrapper AARs."""

import argparse
import hashlib
import io
from pathlib import Path
import subprocess
import tarfile
import zipfile


PYTHON_SHA256 = "423c80b957330cf0ebb92b9b20f2294c292144a441bdb0ceb8c69d14928e569b"
CERTIFI_SHA256 = "e9b54de76e530bb6cf0ccd036787103acb58fd42ba930ea8bf948ddbaa2f61d1"
YTDLP_AAR_SHA256 = "579b5fb480892b1abc2b218c2089699d52759cc8d7ba256bf876453f0365faef"
FFMPEG_AAR_SHA256 = "0a87ffa6cf912b0fe76c1a99b9107f543ee2f247935fae2c71f0822eb7bc5f49"
NATIVE = "jni/arm64-v8a/"


def verify(path, expected):
    if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        raise ValueError(f"Unexpected SHA-256 for {path}")


def python_payload(distribution, certificates, linker_input):
    output = io.BytesIO()
    libraries = {
        "libpython3.14.so", "libssl_python.so", "libcrypto_python.so",
        "libsqlite3_python.so", "libsqlite3.so.0",
    }
    with tarfile.open(distribution, "r:gz") as source, zipfile.ZipFile(
        output, "w", zipfile.ZIP_DEFLATED
    ) as archive:
        for member in source.getmembers():
            name = member.name.removeprefix("./prefix/lib/")
            if name in libraries or (name.startswith("python3.14/") and member.isfile()):
                with source.extractfile(member) as stream:
                    content = stream.read()
                if name == linker_input.name:
                    linker_input.write_bytes(content)
                archive.writestr(f"usr/lib/{name}", content)
        with zipfile.ZipFile(certificates) as wheel:
            archive.writestr("usr/etc/tls/cert.pem", wheel.read("certifi/cacert.pem"))
            license_file = next(name for name in wheel.namelist() if name.endswith("/LICENSE"))
            archive.writestr("usr/etc/tls/CERTIFI-LICENSE", wheel.read(license_file))
    return output.getvalue()


def replace_native(source, destination, files):
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(
        destination, "w", zipfile.ZIP_DEFLATED
    ) as packaged:
        for entry in original.infolist():
            if not entry.filename.startswith("jni/"):
                packaged.writestr(entry, original.read(entry))
        for filename, data in files.items():
            packaged.writestr(NATIVE + filename, data)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for argument in ("python", "certificates", "compiler", "qjs", "ffmpeg-dir", "library-aar", "ffmpeg-aar", "output"):
        parser.add_argument(f"--{argument}", type=Path, required=True)
    args = parser.parse_args()

    verify(args.python, PYTHON_SHA256)
    verify(args.certificates, CERTIFI_SHA256)
    verify(args.library_aar, YTDLP_AAR_SHA256)
    verify(args.ffmpeg_aar, FFMPEG_AAR_SHA256)
    args.output.mkdir(parents=True, exist_ok=True)
    library = args.output / "libpython3.14.so"
    launcher = args.output / "libpython.so"
    payload = python_payload(args.python, args.certificates, library)
    subprocess.run([
        str(args.compiler), "--target=aarch64-linux-android26", "-O2",
        "-Wl,-z,max-page-size=16384", str(Path(__file__).with_name("android-python-launcher.c")),
        str(library), "-o", str(launcher),
    ], check=True)
    replace_native(args.library_aar, args.output / "library.aar", {
        "libpython.so": launcher.read_bytes(),
        "libpython.zip.so": payload,
        "libqjs.so": args.qjs.read_bytes(),
    })

    # The existing wrapper initializes its ZIP even though this FFmpeg is statically linked.
    ffmpeg_payload = io.BytesIO()
    with zipfile.ZipFile(ffmpeg_payload, "w"):
        pass
    replace_native(args.ffmpeg_aar, args.output / "ffmpeg.aar", {
        "libffmpeg.so": (args.ffmpeg_dir / "ffmpeg").read_bytes(),
        "libffprobe.so": (args.ffmpeg_dir / "ffprobe").read_bytes(),
        "libffmpeg.zip.so": ffmpeg_payload.getvalue(),
    })


if __name__ == "__main__":
    main()
