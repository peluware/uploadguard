#!/usr/bin/env python3
"""End-to-end test suite for UploadGuard.

Brings up a real S3-compatible backend and the shaded jar in Docker, then uploads with
`curl -X PUT --data-binary @file "<presigned-url>"`, exactly like a client would.

URLs are presigned with boto3, the reference signer, for http://uploadguard:8080, and curl runs on
the same Docker network, so the Host header it sends is the one the signature covers and nothing
depends on the host's port forwarding. Any change UploadGuard made to the host, the path, the query
or the signed headers would come back as SignatureDoesNotMatch from the backend.

    python run-tests.py            run everything
    python run-tests.py --keep     leave the stack up afterwards
"""
import hashlib
import os
import random
import shutil
import subprocess
import sys
import zipfile

sys.stdout.reconfigure(line_buffering=True)

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
FIXTURES = os.path.join(HERE, "fixtures")
JAR = os.path.join(ROOT, "target", "uploadguard.jar")

# test/ is mounted at /work in the tools container, so the fixtures land here
CONTAINER_FIXTURES = "/work/fixtures"


def exe(name):
    """Resolves a command to a real path. On Windows the launcher is often a .cmd, which
    CreateProcess cannot start by bare name, so the resolved path is what gets used."""
    found = shutil.which(name)
    if found is None:
        return None
    return ["cmd", "/c", found] if found.lower().endswith((".cmd", ".bat")) else [found]


DOCKER = exe("docker")
MVN = exe("mvn")
COMPOSE = (DOCKER or ["docker"]) + ["compose", "-f", os.path.join(HERE, "docker-compose.yml")]

# Fixed credentials, imported into the backend so nothing has to be parsed out of its output.
KEY_ID = "GK1234567890abcdef12345678"
SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
CREDS = ["-e", f"AWS_ACCESS_KEY_ID={KEY_ID}", "-e", f"AWS_SECRET_ACCESS_KEY={SECRET}"]

DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

# --edge signs for the reverse proxy in front, so the whole chain is exercised; without it the client
# talks to the validating proxy directly, which is the only way to cover its relaying of every verb.
THROUGH_EDGE = "--edge" in sys.argv
SIGNED_ENDPOINT = "http://edge" if THROUGH_EDGE else "http://uploadguard:8080"

passed, failed = 0, 0


# --------------------------------------------------------------------------- helpers

def run(cmd, check=True, quiet=False):
    r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if check and r.returncode != 0 and not quiet:
        print(f"!! command failed: {' '.join(cmd)}\n{r.stdout}\n{r.stderr}")
    return r


def garage(*args, quiet=True):
    return run([*COMPOSE, "exec", "-T", "storage", "/garage", *args], check=not quiet, quiet=quiet)


def tool(*args):
    env = [*CREDS, "-e", f"SIGNED_ENDPOINT={SIGNED_ENDPOINT}"]
    r = run([*COMPOSE, "exec", "-T", *env, "tools", "python", "s3tool.py", *args])
    return r.stdout.strip()


def curl(*args):
    """curl from inside the network, so the published port plays no part."""
    return run([*COMPOSE, "exec", "-T", "tools", "curl", *args], check=False).stdout.strip()


def status_of(*args):
    return curl("-s", "-o", "/dev/null", "-w", "%{http_code}", *args)


def presign(key, content_type="", operation="put_object"):
    return tool("presign", key, content_type, operation)


def refused(status):
    """A rejection mid-upload can surface as 502 through the edge: nginx is still writing the body
    when the proxy answers and closes, and treats that as a failed upstream. The status the client
    ends up with depends on how much of the body was left; what must hold either way is that nothing
    reached the backend."""
    return status == "415" or (THROUGH_EDGE and status == "502")


def check(name, got, want):
    global passed, failed
    ok = got == want
    if ok:
        passed += 1
    else:
        failed += 1
    print(f"  {'PASS' if ok else 'FAIL'}  {name}" + ("" if ok else f"   expected {want!r}, got {got!r}"))


def put(url, fixture, content_type="__declared__", extra=()):
    """Uploads a fixture with curl and returns the HTTP status as a string."""
    args = ["-X", "PUT", "--data-binary", "@" + CONTAINER_FIXTURES + "/" + fixture]
    if content_type == "__none__":
        args += ["-H", "Content-Type:"]  # curl drops the header entirely
    elif content_type != "__declared__":
        args += ["-H", "Content-Type: " + content_type]
    return status_of(*args, *extra, url)


def sha256(fixture):
    h = hashlib.sha256()
    with open(os.path.join(FIXTURES, fixture), "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def size(fixture):
    return str(os.path.getsize(os.path.join(FIXTURES, fixture)))


# --------------------------------------------------------------------------- setup

def make_fixtures():
    os.makedirs(FIXTURES, exist_ok=True)
    random.seed(7)
    png_head = bytes([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]) + b"\x00\x00\x00\rIHDR"
    files = {
        # Valid magic bytes followed by padding, so the body is larger than the prefix that gets
        # inspected and the streaming path is exercised.
        "good.png": png_head + bytes(random.getrandbits(8) for _ in range(90000)),
        "doc.pdf": b"%PDF-1.7\n%\xe2\xe3\xcf\xd3\n" + b"1 0 obj\n<<>>\nendobj\n" * 4000,
        "good.gif": b"GIF89a" + bytes(random.getrandbits(8) for _ in range(20000)),
        "real.svg": b'<svg xmlns="http://www.w3.org/2000/svg"><rect width="9" height="9"/></svg>',
        "page.html": b"<!DOCTYPE html><html><body>" + b"x" * 9000 + b"</body></html>",
        # Content that will not back up what gets declared
        "fake.png": bytes(random.getrandbits(8) for _ in range(40000)),
        "empty.png": b"",
    }
    for name, data in files.items():
        path = os.path.join(FIXTURES, name)
        if not os.path.exists(path) or os.path.getsize(path) != len(data):
            with open(path, "wb") as f:
                f.write(data)

    # A real OOXML container. From its prefix this is only detectable as x-tika-ooxml, never as the
    # exact docx flavour, so it exercises the "less precise, not contradicted" branch.
    docx = os.path.join(FIXTURES, "doc.docx")
    if not os.path.exists(docx):
        with zipfile.ZipFile(docx, "w") as z:
            z.writestr("[Content_Types].xml",
                       '<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>')
            z.writestr("word/document.xml", "<w:document>" + "x" * 5000 + "</w:document>")

    # 20 MB, built once: proves a large body is streamed through rather than buffered
    big = os.path.join(FIXTURES, "big.png")
    if not os.path.exists(big) or os.path.getsize(big) != 20 * 1024 * 1024 + len(png_head):
        with open(big, "wb") as f:
            f.write(png_head)
            for _ in range(20):
                f.write(os.urandom(1024 * 1024))

    # 20 MB of noise: the rejection has to reach the client mid-upload
    bigfake = os.path.join(FIXTURES, "big-fake.png")
    if not os.path.exists(bigfake) or os.path.getsize(bigfake) != 20 * 1024 * 1024:
        with open(bigfake, "wb") as f:
            for _ in range(20):
                f.write(os.urandom(1024 * 1024))


def bootstrap_storage():
    """Single-node layout, a fixed access key and the bucket. Every step is idempotent."""
    node = garage("node", "id", "-q").stdout.strip().splitlines()[-1].split("@")[0]
    garage("layout", "assign", "-z", "dc1", "-c", "1G", node)
    garage("layout", "apply", "--version", "1")
    garage("key", "import", "--yes", "-n", "ug-test", KEY_ID, SECRET)
    garage("bucket", "create", "uploads")
    garage("bucket", "allow", "--read", "--write", "--owner", "uploads", "--key", KEY_ID)


def build_jar():
    print("== building the jar")
    if MVN:
        r = run(MVN + ["-q", "-B", "-f", os.path.join(ROOT, "pom.xml"), "clean", "package"])
    else:
        print("   mvn not found, building in Docker")
        r = run(DOCKER + ["run", "--rm", "-v", f"{ROOT}:/src", "-w", "/src",
                          "maven:3.9-eclipse-temurin-21", "mvn", "-q", "-B", "clean", "package"])
    if r.returncode != 0 or not os.path.exists(JAR):
        sys.exit("build failed")
    print(f"   {os.path.relpath(JAR, ROOT)} ({os.path.getsize(JAR) // 1024} KB)")


def up():
    print(f"== bringing the stack up, signing for {SIGNED_ENDPOINT}")
    run([*COMPOSE, "up", "-d", "--build", "--wait", "storage", "tools"])
    bootstrap_storage()
    run([*COMPOSE, "up", "-d", "--build", "--wait", "uploadguard"])
    if THROUGH_EDGE:
        run([*COMPOSE, "up", "-d", "--wait", "edge"])
    print(f"   bucket {tool('init')} ready")


# --------------------------------------------------------------------------- tests

def test_accepted():
    print("\n== accepted uploads (the content backs up the declared type)")
    cases = [
        ("plain key", "ok.png", "good.png", "image/png"),
        ("key with reserved characters", "weird/a&b=c+d,e(1)[2].png", "good.png", "image/png"),
        ("key with a double slash", "double//slash.png", "good.png", "image/png"),
        ("key with a percent sign", "100%25 done.png", "good.png", "image/png"),
        ("pdf", "docs/report.pdf", "doc.pdf", "application/pdf"),
        ("gif", "anim.gif", "good.gif", "image/gif"),
        ("20 MB body is streamed", "big.png", "big.png", "image/png"),
    ]
    for name, key, fixture, ctype in cases:
        status = put(presign(key, ctype), fixture, ctype)
        check(f"{name}: HTTP 200", status, "200")
        if status == "200":
            stored_size, digest, stored_type = tool("check", key).split()
            check(f"{name}: bytes stored intact", digest, sha256(fixture))
            check(f"{name}: size", stored_size, size(fixture))
            check(f"{name}: Content-Type preserved", stored_type, ctype)


def test_non_ascii_keys():
    print("\n== keys with spaces, accents and n-tilde (percent-encoded inside the signed path)")
    keys = [
        "fotos/mi añejo ñandú ácentós.png",
        "años/2026/resumen año.png",
    ]
    for key in keys:
        status = put(presign(key, "image/png"), "good.png", "image/png")
        check(f"{key}: HTTP 200", status, "200")
        if status == "200":
            check(f"{key}: bytes stored intact", tool("check", key).split()[1], sha256("good.png"))


def test_rejected():
    print("\n== rejected uploads (nothing must reach the backend)")
    cases = [
        ("random bytes declared as png", "bad/fake.png", "fake.png", "image/png"),
        ("html declared as png", "bad/page.png", "page.html", "image/png"),
        ("pdf declared as png", "bad/pdf-as-png.png", "doc.pdf", "image/png"),
        ("png declared as pdf", "bad/png-as-pdf.pdf", "good.png", "application/pdf"),
        ("svg declared as png", "bad/svg-as-png.png", "real.svg", "image/png"),
        ("20 MB of noise declared as png", "bad/big-fake.png", "big-fake.png", "image/png"),
    ]
    for name, key, fixture, ctype in cases:
        check(f"{name}: refused", refused(put(presign(key, ctype), fixture, ctype)), True)
        check(f"{name}: not stored", tool("absent", key), "ABSENT")


def test_signer_decides_the_type():
    print("\n== the signature decides which types may be uploaded, this service only checks honesty")
    # Nothing here keeps a type off the storage: if the backend signed it, the backend wanted it.
    # Blocking SVG, for instance, is the signer's decision, not this service's.
    cases = [
        ("real svg", "assets/real.svg", "real.svg", "image/svg+xml"),
        ("real html", "pages/page.html", "page.html", "text/html"),
        # Only detectable as x-tika-ooxml from a prefix: must not be refused for being less precise
        ("real docx", "docs/report.docx", "doc.docx", DOCX),
    ]
    for name, key, fixture, ctype in cases:
        status = put(presign(key, ctype), fixture, ctype)
        check(f"{name} under a signature for it: HTTP 200", status, "200")
        if status == "200":
            _, digest, stored_type = tool("check", key).split()
            check(f"{name}: bytes stored intact", digest, sha256(fixture))
            check(f"{name}: Content-Type preserved", stored_type, ctype)

    # ...but a body that contradicts the signed type is refused whatever that type is
    check("20 MB png under a signature for mp4: refused",
          refused(put(presign("x.mp4", "video/mp4"), "big.png", "video/mp4")), True)
    check("not stored", tool("absent", "x.mp4"), "ABSENT")


def test_any_verb_with_a_body():
    print("\n== the check follows the body, not the verb")
    # The URL is signed for PUT, so the backend refuses anything else with 403. That is what makes
    # these assertions sharp: a 415 can only come from this proxy, a 403 only from the backend.
    url = presign("verbs.png", "image/png")

    check("POST with a contradicting body: HTTP 415 from the proxy",
          put(url, "fake.png", "image/png", ["-X", "POST"]), "415")
    check("PATCH with a contradicting body: HTTP 415 from the proxy",
          put(url, "page.html", "image/png", ["-X", "PATCH"]), "415")
    # Body that holds up: the proxy relays it and the backend has the last word, whatever it says
    relayed = put(url, "good.png", "image/png", ["-X", "POST"])
    check("POST with a body that holds up is not refused here", relayed != "415", True)
    print(f"        (backend answered the POST with {relayed})")


def test_protocol():
    print("\n== protocol handling")
    url = presign("proto.png", "image/png")

    # No declared type is no claim, so it is relayed. Proven by the effect, not by reading an error:
    # signed without a content type and sent without the header, the object has to land.
    untyped = presign("untyped.bin")
    check("body with no Content-Type is relayed: HTTP 200", put(untyped, "good.png", "__none__"), "200")
    check("untyped body stored intact", tool("check", "untyped.bin").split()[1], sha256("good.png"))

    # Both framing headers at once: the proxy cannot relay an ambiguous message faithfully
    check("Content-Length and Transfer-Encoding together: refused here",
          "400" in tool("smuggle", "smuggled.bin"), True)
    check("smuggled request never reached the backend", tool("absent", "smuggled.bin"), "ABSENT")

    # No Content-Length: inspected all the same and relayed chunked, never refused with 411
    chunked = put(url, "good.png", "image/png", ["-H", "Transfer-Encoding: chunked"])
    check("chunked body is not refused by the proxy", chunked != "411", True)
    print(f"        (chunked upload came back {chunked})")

    # Nothing to read, nothing to contradict: relayed, and the empty object lands
    empty_url = presign("empty.png", "image/png")
    check("empty body is relayed: HTTP 200", put(empty_url, "empty.png", "image/png"), "200")
    check("empty object stored with 0 bytes", tool("check", "empty.png").split()[0], "0")

    # Methods are not filtered here. Signed for their own method, a GET and a DELETE go through and
    # succeed, which also exercises relaying a response body and a 204 with no body at all.
    get_url = presign("ok.png", operation="get_object")
    check("GET is relayed: HTTP 200", status_of(get_url), "200")
    check("GET relays the whole body back",
          curl("-s", "-o", "/dev/null", "-w", "%{size_download}", get_url), size("good.png"))

    delete_url = presign("ok.png", operation="delete_object")
    check("DELETE is relayed: HTTP 204", status_of("-X", "DELETE", delete_url), "204")
    check("the object is gone", tool("absent", "ok.png"), "ABSENT")

    status = status_of("-X", "OPTIONS", "-H", "Origin: https://example.org",
                       "-H", "Access-Control-Request-Method: PUT", url)
    check("OPTIONS preflight is relayed (not 502)", status != "502", True)
    print(f"        (backend answered the preflight with {status})")


def test_signature_is_untouched():
    print("\n== signature invariants")
    # A tampered signature has to come back as a backend refusal, never as a proxy error: that is
    # what proves the request reached the backend byte for byte as the client sent it.
    url = presign("tamper.png", "image/png")
    broken = url[:-4] + ("abcd" if not url.endswith("abcd") else "0000")
    check("tampered signature is rejected by the backend: HTTP 403",
          put(broken, "good.png", "image/png"), "403")


# --------------------------------------------------------------------------- main

def main():
    if DOCKER is None:
        sys.exit("docker was not found on PATH")
    make_fixtures()
    build_jar()
    up()
    test_accepted()
    test_non_ascii_keys()
    test_rejected()
    test_signer_decides_the_type()
    test_any_verb_with_a_body()
    test_protocol()
    test_signature_is_untouched()

    print(f"\n== {passed} passed, {failed} failed")
    if "--keep" not in sys.argv:
        run([*COMPOSE, "down", "-v"], check=False)
    else:
        print("   stack left running (--keep)")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
