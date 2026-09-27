"""Small inspectable model language: named inputs, expressions and outputs; no eval/exec."""
from __future__ import annotations
import ast
import hashlib
import math
import operator
import re
from .model_workbook import parse_workbook

INPUTS = {
    "headline": "Publisher headline (text)", "text": "Headline plus supplied excerpt (text)",
    "publisher": "Attributed publisher (text)", "age_seconds": "Seconds since event time, clamped at zero",
    "observation_count": "Retained source observations", "source_count": "Distinct observed feed/source identities",
    "ticker_count": "Distinct associated tickers", "tickers": "Associated ticker symbols (list)", "embedding": "Encoded semantic vector (list of numbers)",
    "half_lives_seconds": "Configured decay horizons (seconds)", "prior_mass": "Configured shrinkage mass",
}
STARTER = '# These are separate measurements, not a trained price prediction.\nname News information\nparam half_life = 1800\ninput age = age_seconds\ninput vector = embedding\ninput sources = source_count\ninput links = ticker_count\noutput freshness = decay(age, half_life)\noutput coverage = sources\noutput ticker_links = links\noutput vector_length = norm(vector)\n# Controls contribution to the tensor. Must remain between 0 and 1.\noutput tensor_weight = 1\n\n# Set notify from your own relevance/importance logic. Routes may be any model-selected tickers.\ninput affected = tickers\noutput notify = False\noutput notify_symbols = affected\n\n# Called with a ticker by News(rating). This example rates freshness, not price direction.\nsymbol\ninput ticker = symbol\ninput items = articles\noutput article_count = length(items)\noutput rating = mean(column(items, "outputs.freshness")) if length(items) > 0 else 0\nend symbol\n\nsheet\n{\n  "sheets": [\n    {\n      "name": "Information",\n      "columns": [\n        {\n          "id": "headline",\n          "path": "/headline",\n          "type": "text"\n        },\n        {\n          "id": "freshness",\n          "path": "/outputs/freshness",\n          "type": "number"\n        },\n        {\n          "id": "sources",\n          "path": "/outputs/coverage",\n          "type": "number"\n        },\n        {\n          "id": "tickers",\n          "path": "/tickers",\n          "type": "json"\n        },\n        {\n          "id": "publisher",\n          "path": "/publisher"\n        },\n        {\n          "id": "state",\n          "path": "/rating_state"\n        },\n        {\n          "id": "rated",\n          "path": "/rated_at"\n        }\n      ]\n    },\n    {\n      "name": "Evidence",\n      "rows": "/associations",\n      "columns": [\n        {\n          "id": "headline",\n          "source": "record",\n          "path": "/headline"\n        },\n        {\n          "id": "ticker",\n          "path": "/ticker"\n        },\n        {\n          "id": "kind",\n          "path": "/kind"\n        },\n        {\n          "id": "evidence",\n          "path": "/evidence"\n        }\n      ]\n    }\n  ]\n}\nend sheet\n'

def component(vector, index):
    if not isinstance(index, (int, float)) or int(index) != index or not 0 <= index < len(vector):
        raise ValueError("component index outside vector")
    return vector[int(index)]

def dot(a, b):
    if len(a) != len(b):
        raise ValueError("dot inputs must have the same dimension")
    return sum(x * y for x, y in zip(a, b, strict=True))

def decay(age, half_life):
    if half_life <= 0:
        raise ValueError("decay half-life must be positive")
    return 2 ** (-max(0, age) / half_life)

def field(record, path):
    for part in path.split("."):
        record = record[part]
    return record

def column(records, path):
    return [field(record, path) for record in records]

SYMBOL_INPUTS = {"symbol": "Requested ticker", "articles": "Current rated articles associated with this ticker",
                 "now": "Evaluation time, Unix seconds",
                 "news_tensor": "Weighted symbol information vector (weighted News engine only)",
                 "tensor_reference": "Saved tensor location, dimensions and provenance (weighted News engine only)"}
SYMBOL_BLOCK = re.compile(r"^symbol[^\S\r\n]*\r?\n(.*?)^end symbol[^\S\r\n]*(?:\r?\n|$)", re.M | re.S)

FUNCTIONS = {"field": field, "column": column,"abs": abs, "min": min, "max": max, "sqrt": math.sqrt, "log": math.log,
             "exp": math.exp, "clamp": lambda x, low, high: min(high, max(low, x)),
             "decay": decay, "contains": lambda text, term: str(term).casefold() in str(text).casefold(),
             "contains_any": lambda text, terms: any(str(term).casefold() in str(text).casefold() for term in terms),
             "length": len, "component": component, "dot": dot,
             "norm": lambda vector: math.sqrt(dot(vector, vector)), "sum": sum,
             "mean": lambda values: sum(values) / len(values)}
BIN = {ast.Add: operator.add, ast.Sub: operator.sub, ast.Mult: operator.mul, ast.Div: operator.truediv,
       ast.Pow: operator.pow, ast.Mod: operator.mod}
CMP = {ast.Eq: operator.eq, ast.NotEq: operator.ne, ast.Lt: operator.lt, ast.LtE: operator.le,
       ast.Gt: operator.gt, ast.GtE: operator.ge}

def expression(source, names):
    tree = ast.parse(source, mode="eval").body
    nodes = list(ast.walk(tree))
    if len(nodes) > 512 or len(source) > 4000:
        raise ValueError("Expression too large")
    for node in nodes:
        if isinstance(node, ast.Name) and node.id not in names and node.id not in FUNCTIONS:
            raise ValueError("Unknown variable: " + node.id)
        if isinstance(node, ast.Call) and (not isinstance(node.func, ast.Name) or node.func.id not in FUNCTIONS or node.keywords):
            raise ValueError("Only documented model functions may be called")
        if not isinstance(node, (ast.Constant, ast.Name, ast.Load, ast.BinOp, ast.UnaryOp, ast.BoolOp,
                                 ast.Compare, ast.IfExp, ast.Call, ast.List, ast.Tuple, ast.Dict,
                                 *BIN, *CMP, ast.USub, ast.UAdd, ast.Not, ast.And, ast.Or)):
            raise ValueError("Unsupported expression: " + type(node).__name__)
        if isinstance(node, ast.Constant) and not isinstance(node.value, (str, int, float, bool)):
            raise ValueError("Only text, boolean and numeric literals are supported")
    return tree

def value(node, data):
    if isinstance(node, ast.Constant): return node.value
    if isinstance(node, ast.Name): return data[node.id]
    if isinstance(node, (ast.List, ast.Tuple)): return [value(item, data) for item in node.elts]
    if isinstance(node, ast.Dict): return {value(k, data): value(v, data) for k, v in zip(node.keys, node.values, strict=True)}
    if isinstance(node, ast.BinOp):
        a, b = value(node.left, data), value(node.right, data)
        if not isinstance(a, (int, float)) or not isinstance(b, (int, float)): raise ValueError("Arithmetic requires numbers")
        if isinstance(node.op, ast.Pow) and abs(b) > 1024: raise ValueError("Exponent outside supported range")
        return BIN[type(node.op)](a, b)
    if isinstance(node, ast.UnaryOp):
        v = value(node.operand, data)
        return not v if isinstance(node.op, ast.Not) else -v if isinstance(node.op, ast.USub) else +v
    if isinstance(node, ast.BoolOp):
        return all(value(v, data) for v in node.values) if isinstance(node.op, ast.And) else any(value(v, data) for v in node.values)
    if isinstance(node, ast.Compare):
        values = [value(node.left, data)] + [value(v, data) for v in node.comparators]
        return all(CMP[type(op)](a, b) for op, a, b in zip(node.ops, values, values[1:]))
    if isinstance(node, ast.IfExp): return value(node.body if value(node.test, data) else node.orelse, data)
    if isinstance(node, ast.Call): return FUNCTIONS[node.func.id](*[value(v, data) for v in node.args])
    raise ValueError("Unsupported expression")

class Program:
    def __init__(self, source: str, inputs=INPUTS, allow_symbol=True):
        if len(source) > 30000: raise ValueError("Model code must be at most 30,000 characters")
        self.version = hashlib.sha256(source.encode()).hexdigest()
        source, self.workbook = parse_workbook(source)
        blocks = list(SYMBOL_BLOCK.finditer(source)); self.symbol = None
        if len(blocks) > 1 or blocks and not allow_symbol: raise ValueError("Use one symbol block; nesting is unsupported")
        if blocks:
            match = blocks[0]
            self.symbol = Program(match.group(1), SYMBOL_INPUTS, allow_symbol=False)
            source = source[:match.start()] + re.sub(r"[^\r\n]", " ", match.group()) + source[match.end():]
        if any(line.strip() in ("symbol", "end symbol") for line in source.splitlines()):
            raise ValueError("Symbol program needs symbol and end symbol lines")
        self.steps = []; self.outputs = []; self.parameters = {}; names = set()
        for line_number, raw in enumerate(source.splitlines(), 1):
            line = raw.strip()
            if not line or line.startswith("#") or line.startswith("name "): continue
            try:
                match = re.fullmatch(r"(input|param|let|output)\s+([a-z][a-z0-9_]{0,39})\s*=\s*(.+)", line)
                if not match: raise ValueError("Use input alias = field, let name = expression, or output name = expression")
                kind, name, source_value = match.groups()
                if name in names or name in FUNCTIONS: raise ValueError("Duplicate/reserved name: " + name)
                if kind == "input":
                    if source_value not in inputs and not re.fullmatch(r"custom\.[a-z][a-z0-9_]{0,39}", source_value): raise ValueError("Unknown input: " + source_value)
                    parsed = source_value
                elif kind == "param":
                    parsed = expression(source_value, set())
                    if any(not isinstance(n, (ast.Constant, ast.UnaryOp, ast.USub, ast.UAdd, ast.List, ast.Tuple, ast.Dict, ast.Load)) for n in ast.walk(parsed)):
                        raise ValueError("Parameters must be literal numbers, text, booleans, lists or objects")
                    parameter = value(parsed, {})
                    import json
                    json.dumps(parameter, allow_nan=False)
                    self.parameters[name] = parameter
                else: parsed = expression(source_value, names)
                self.steps.append((kind, name, parsed)); names.add(name)
                if kind == "output": self.outputs.append(name)
            except (ValueError, SyntaxError) as error: raise ValueError(f"Line {line_number}: {error}") from error
        if not self.outputs: raise ValueError("Declare at least one output")
        if len(self.steps) > 128: raise ValueError("At most 128 input/equation/output declarations")

    def run(self, inputs):
        data = {}; outputs = {}
        for kind, name, source in self.steps:
            result = inputs[source] if kind == "input" else value(source, data)
            if isinstance(result, (int, float)) and not math.isfinite(result): raise ValueError(name + " is not finite")
            data[name] = result
            if kind == "output":
                import json
                # JSON values are the display contract: scalars, text, labels, vectors and named records.
                encoded = json.dumps(result, allow_nan=False)
                if len(encoded) > 100000: raise ValueError("Output too large: " + name)
                outputs[name] = result
        if "tensor_weight" in outputs and (not isinstance(outputs["tensor_weight"], (int, float)) or not 0 <= outputs["tensor_weight"] <= 1):
            raise ValueError("tensor_weight must be in [0,1]")
        return outputs
