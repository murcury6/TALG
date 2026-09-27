"""Model data schema. Legacy presentation hints are discarded; the app owns visuals."""
import json
import re

BLOCK = re.compile(r"^sheet[^\S\r\n]*\r?\n(.*?)^end sheet[^\S\r\n]*(?:\r?\n|$)", re.M | re.S)

def parse_workbook(source):
    matches = list(BLOCK.finditer(source))
    if len(matches) > 1: raise ValueError("Use one workbook block with a sheets array")
    if not matches:
        if any(line.strip() in ("sheet", "end sheet") for line in source.splitlines()):
            raise ValueError("Workbook needs sheet and end sheet lines")
        return source, None
    match = matches[0]
    workbook = data_schema(json.loads(match.group(1)))
    validate_workbook(workbook)
    executable = source[:match.start()] + re.sub(r"[^\r\n]", " ", match.group()) + source[match.end():]
    if any(line.strip() in ("sheet", "end sheet") for line in executable.splitlines()):
        raise ValueError("Unexpected workbook delimiter")
    return executable, workbook

def data_schema(workbook):
    # Only schema positions are normalized; arbitrary output records may contain these names.
    import copy
    result = copy.deepcopy(workbook)
    if isinstance(result, dict) and isinstance(result.get("sheets"), list):
        for sheet in result["sheets"]:
            if not isinstance(sheet, dict): continue
            for key in ("sort", "rowHeight", "details"): sheet.pop(key, None)
            if isinstance(sheet.get("columns"), list):
                for column in sheet["columns"]:
                    if isinstance(column, dict):
                        for key in ("label", "format", "precision", "width", "align", "missing"): column.pop(key, None)
    return result

def validate_workbook(workbook):
    def require(ok, message):
        if not ok: raise ValueError(message)
    def keys(obj, allowed):
        require(isinstance(obj, dict), "Workbook entries must be objects")
        require(not obj.keys() - set(allowed), "Unsupported workbook fields: " + str(obj.keys() - set(allowed)))
    def pointer(obj, key):
        if key in obj: require(isinstance(obj[key], str) and re.fullmatch(r"(?:/(?:[^~]|~[01])*)?", obj[key]) is not None, key + " must be a JSON pointer")
    def choice(obj, key, allowed):
        if key in obj: require(isinstance(obj[key], str) and obj[key] in allowed, "Unsupported " + key)
    workbook = data_schema(workbook)
    keys(workbook, ["sheets"])
    sheets = workbook.get("sheets")
    require(isinstance(sheets, list) and 1 <= len(sheets) <= 16, "Workbook needs 1–16 sheets")
    names = set()
    for sheet in sheets:
        keys(sheet, ["name", "rows", "columns"])
        name = sheet.get("name")
        require(isinstance(name, str) and bool(name.strip()) and name not in names, "Sheet names must be nonempty and unique")
        names.add(name); pointer(sheet, "rows")
        columns = sheet.get("columns")
        require(isinstance(columns, list) and 1 <= len(columns) <= 128, "Each sheet needs 1–128 columns")
        ids = set()
        for column in columns:
            keys(column, ["id", "path", "source", "type"])
            identity = column.get("id")
            require(isinstance(identity, str) and bool(identity.strip()) and identity not in ids, "Column ids must be nonempty and unique")
            ids.add(identity)
            require("path" in column, "Each column needs a JSON pointer path"); pointer(column, "path")
            choice(column, "source", ["row", "record"]); choice(column, "type", ["auto", "text", "number", "boolean", "json"])
