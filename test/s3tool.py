"""Presign PUT URLs and inspect stored objects. boto3 is the reference signer:
if a request that went through UploadGuard still verifies, the signature was preserved."""
import hashlib
import os
import sys

import boto3
from botocore.client import Config

BUCKET = os.environ["BUCKET"]


def client(endpoint):
    return boto3.client(
        "s3",
        endpoint_url=endpoint,
        config=Config(signature_version="s3v4", s3={"addressing_style": "path"}),
    )


def main():
    cmd = sys.argv[1]
    direct = client(os.environ["STORAGE_ENDPOINT"])

    if cmd == "init":
        existing = [b["Name"] for b in direct.list_buckets()["Buckets"]]
        if BUCKET not in existing:
            direct.create_bucket(Bucket=BUCKET)
        print(BUCKET)

    elif cmd == "presign":
        key, content_type = sys.argv[2], sys.argv[3]
        operation = sys.argv[4] if len(sys.argv) > 4 else "put_object"
        params = {"Bucket": BUCKET, "Key": key}
        if operation == "put_object" and content_type:
            params["ContentType"] = content_type
        # Signed against the public endpoint: the signature covers host, method, path and headers.
        print(client(os.environ["SIGNED_ENDPOINT"]).generate_presigned_url(
            operation, Params=params, ExpiresIn=600))

    elif cmd == "check":
        key = sys.argv[2]
        obj = direct.get_object(Bucket=BUCKET, Key=key)
        body = obj["Body"].read()
        # An object uploaded without the header has no content type at all
        print(f"{len(body)} {hashlib.sha256(body).hexdigest()} {obj.get('ContentType', '-')}")

    elif cmd == "smuggle":
        # A request carrying both framing headers, which curl will not send. The proxy has to refuse
        # it by itself, so the status line is its own answer and the backend never sees the request.
        import socket
        from urllib.parse import urlsplit

        crlf = chr(13) + chr(10)
        url = client(os.environ["SIGNED_ENDPOINT"]).generate_presigned_url(
            "put_object",
            Params={"Bucket": BUCKET, "Key": sys.argv[2], "ContentType": "image/png"},
            ExpiresIn=600,
        )
        parts = urlsplit(url)
        body = bytes([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])
        head = crlf.join([
            f"PUT {parts.path}?{parts.query} HTTP/1.1",
            f"Host: {parts.netloc}",
            "Content-Type: image/png",
            f"Content-Length: {len(body)}",
            "Transfer-Encoding: chunked",
            "Connection: close",
            "",
            "",
        ]).encode()
        with socket.create_connection((parts.hostname, parts.port or 80), timeout=10) as sock:
            sock.sendall(head + body)
            answer = sock.recv(256).decode("latin-1", "replace")
        print(answer.splitlines()[0] if answer else "(no answer)")

    elif cmd == "absent":
        from botocore.exceptions import ClientError
        try:
            direct.head_object(Bucket=BUCKET, Key=sys.argv[2])
            print("PRESENT")
        except ClientError:
            print("ABSENT")


if __name__ == "__main__":
    main()
