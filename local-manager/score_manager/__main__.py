"""Local CLI adapter. JSON arguments feed only the closed workflow action set."""
import argparse
import json
from pathlib import Path
import sqlite3
import sys

from .errors import Invalid
from .store import Catalog
from .validation import parse_json
from .workflow import ACTIONS, FORMAT_NOTICE, Workflow, export_committed_bundle


def main(argv=None):
    parser = argparse.ArgumentParser(description="Local administrator drafting workflow; no login, PDF delivery or network.")
    parser.add_argument("--db", required=True, type=Path, help="committed local workbench path")
    sub = parser.add_subparsers(dest="command", required=True)
    init = sub.add_parser("init", help="explicitly create a new empty workbench; never overwrite")
    init.add_argument("--confirm-create", action="store_true", required=True)
    sub.add_parser("begin", help="create an isolated unconfirmed draft")
    edit = sub.add_parser("edit", help="stage a typed operation; does not save to the committed library")
    edit.add_argument("draft")
    edit.add_argument("action", choices=sorted(ACTIONS))
    edit.add_argument("--request-id", required=True, help="same ID + same input safely replays a double click")
    edit.add_argument("--args", required=True, help="JSON object; paths are local selected files")
    for command in ("status", "cancel", "review", "confirm"):
        child = sub.add_parser(command)
        child.add_argument("draft")
        if command in ("review", "confirm"):
            child.add_argument("--member", required=True, help="member to preview; not an authenticated identity")
        if command == "confirm":
            child.add_argument("--review-hash", required=True)
            child.add_argument("--yes", action="store_true", required=True, help="explicitly commit the reviewed draft")
    export = sub.add_parser("export", help="administrator complete backup; not member PDF distribution")
    export.add_argument("--owner", required=True)
    export.add_argument("--output", required=True, type=Path)
    export.add_argument("--confirm-complete-bundle", action="store_true", required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "init":
            with Catalog(args.db, create=True):
                pass
            result = {"state": "EMPTY", "formatNotice": FORMAT_NOTICE}
        elif args.command == "export":
            result = export_committed_bundle(args.db, args.output, args.owner)
        else:
            workflow = Workflow(args.db)
            if args.command == "begin":
                result = workflow.start()
            elif args.command == "edit":
                result = workflow.edit(args.draft, args.action, parse_json(args.args), request_id=args.request_id)
            elif args.command == "review":
                result = workflow.review(args.draft, args.member)
            elif args.command == "confirm":
                result = workflow.confirm(args.draft, args.member, args.review_hash)
            else:
                result = getattr(workflow, args.command)(args.draft)
        print(json.dumps(result, ensure_ascii=False, allow_nan=False))
        return 0
    except (Invalid, OSError, sqlite3.Error, ValueError, TypeError, KeyError) as error:
        print(json.dumps({"state": "REJECTED", "committed": False, "error": str(error)}, ensure_ascii=False), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
