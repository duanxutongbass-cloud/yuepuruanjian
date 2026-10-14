"""Private parser process. No file output, embedded actions or network calls."""
import json
import math
import sys


def inspect(path):
    # Linux test guard. Windows still has parent timeout/input/page limits;
    # a native Windows job-object memory budget is a later packaging gate.
    try:
        import resource
        resource.setrlimit(resource.RLIMIT_AS, (768 * 1024**2, 768 * 1024**2))
    except ImportError:
        pass
    from pypdf import PdfReader
    with open(path, "rb") as stream:
        if not stream.read(8).startswith(b"%PDF-"):
            raise ValueError("Missing PDF header")
        stream.seek(0)
        reader = PdfReader(stream, strict=True)
        if reader.is_encrypted:
            raise ValueError("Encrypted PDFs require a separate workflow")
        if not 0 < len(reader.pages) <= 10_000:
            raise ValueError("Invalid page count")
        result = []
        for page in reader.pages:
            media, crop = list(map(float, page.mediabox)), list(map(float, page.cropbox))
            rotation = page.get("/Rotate", 0)
            unit = float(page.get("/UserUnit", 1))
            if (not all(math.isfinite(x) for x in media + crop + [unit])
                    or unit <= 0 or media[2] <= media[0] or media[3] <= media[1]
                    or crop[2] <= crop[0] or crop[3] <= crop[1]
                    or rotation % 90 != 0):
                raise ValueError("Invalid source page geometry")
            contents = page.get_contents()
            if contents is not None:
                # Force stream decompression and content operator parsing.
                if len(contents.get_data()) > 32 * 1024**2:
                    raise ValueError("Page content exceeds validation budget")
                _ = contents.operations
            result.append({"mediaBox": media, "cropBox": crop,
                           "rotation": int(rotation) % 360, "userUnit": unit})
        return result


if __name__ == "__main__":
    try:
        print(json.dumps(inspect(sys.argv[1]), allow_nan=False))
    except Exception as error:
        print(type(error).__name__ + ": " + str(error), file=sys.stderr)
        sys.exit(1)
