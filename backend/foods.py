"""What Lazy Fitness knows about food: calories per portion, and the foods a person has said they do not want.

Calories come from foods.txt (a seed table) and from a `foods` table in the database that remembers what the AI
estimated for foods the seed does not have. The AI is only asked about foods nobody has priced yet. Standard library only.
"""
import re
from pathlib import Path

HERE = Path(__file__).resolve().parent
UNITS = {"piece", "slice", "plate", "bowl", "cup", "glass", "tbsp", "tsp", "handful", "scoop", "serving", "100g"}
MAX_AVOID = 25


def singular(w):
    if w in ("this", "its", "his", "has", "was", "is", "us", "as"):
        return w
    if len(w) > 3 and w.endswith("ies"):
        return w[:-3] + "y"
    if len(w) > 3 and w.endswith("s") and not w.endswith(("ss", "us")):
        return w[:-1]
    return w


def norm(text):
    """Lowercase words, singular, one space: "2 Samosas" and "samosa" both end in "samosa"."""
    return " ".join(singular(w) for w in re.findall(r"[a-z]+", str(text).lower()))


def _load():
    table = {}
    for line in (HERE / "foods.txt").read_text().splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        name, unit, kcal, *rest = [p.strip() for p in line.split("|")]
        if unit not in UNITS:
            raise ValueError(f"foods.txt: unknown unit {unit!r} for {name}")
        entry = (unit, int(kcal))
        for key in [name] + (rest[0].split(",") if rest and rest[0] else []):
            table[norm(key)] = entry
    return table


TABLE = _load()
FOOD_WORDS = {w for k in TABLE for w in k.split()}


# ---------------------------------------------------------------- reading what was eaten
_UNIT_WORDS = {"piece": "piece", "pc": "piece", "slice": "slice", "plate": "plate", "bowl": "bowl", "katori": "bowl",
               "cup": "cup", "glass": "glass", "tbsp": "tbsp", "tablespoon": "tbsp", "spoon": "tbsp", "tsp": "tsp",
               "teaspoon": "tsp", "handful": "handful", "fistful": "handful", "scoop": "scoop", "serving": "serving",
               "g": "100g", "gm": "100g", "gram": "100g"}
_NUMBER_WORDS = {"a": 1, "an": 1, "one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6, "half": 0.5, "couple": 2}
# a portion in another unit, as a share of the table's unit. Any other mismatch is an unknown food: the AI prices it once.
_EQUIV = {("plate", "bowl"): 1.5, ("bowl", "plate"): 2 / 3}
_SIZES = {"small", "big", "large", "medium", "mini", "full", "regular"}
_FILLER = {"of", "some", "about", "around", "approx", "fresh", "hot", "the", "my", "a", "an", "plain", "extra"}
_REDUCERS = {"less", "light", "low", "no", "without", "little", "zero"}
_TRIMMINGS = {"oil", "sugar", "salt", "spice", "spicy", "masala", "ghee", "butter", "gravy", "chilli", "chili"}
_GARNISH = {"coriander", "mint", "lemon", "pepper", "black", "salt", "jeera", "ajwain", "turmeric", "haldi", "masala", "ginger",
            "garlic", "onion", "tomato", "chilli", "chili", "stir", "fried", "grilled", "boiled", "roasted", "steamed", "baked",
            "sauteed", "mild", "spicy"}


def _number(t):
    if re.fullmatch(r"\d+(?:\.\d+)?", t):
        return float(t)
    if re.fullmatch(r"\d+/\d+", t):
        a, b = t.split("/")
        return int(a) / int(b) if int(b) else None
    return _NUMBER_WORDS.get(t)


def parse(text):
    """What was eaten as [(quantity, unit or None, food name)]. Understands "2 samosas and a cup of chai" and the
    "paneer bhurji, 1 bowl" form. Leaves out things like "less oil" and garnish."""
    text = re.sub(r"\([^)]*\)", " ", str(text).lower())
    out = []
    for chunk in re.split(r",|;|\+|&|\band\b|\bwith\b|\bplus\b", text):
        t = [singular(w) if not w[0].isdigit() else w for w in re.findall(r"\d+/\d+|\d+(?:\.\d+)?|[a-z]+", chunk)]
        t = [w for w in t if w not in _SIZES]
        if not t or (t[0] in _REDUCERS and set(t[1:]) <= _TRIMMINGS | _FILLER) or set(t) <= _GARNISH | _FILLER:
            continue
        qty, unit, i = 1.0, None, 0
        if len(t) > 2 and _number(t[0]) is not None and t[1] == "egg":      # "2 egg bhurji": dishes are priced per 2 eggs
            qty, i = _number(t[0]) / 2, 2
        else:
            if _number(t[0]) is not None:
                qty, i = _number(t[0]), 1
            if i < len(t) and t[i] in _UNIT_WORDS:
                unit, i = _UNIT_WORDS[t[i]], i + 1
                if i < len(t) and t[i] == "of":
                    i += 1
        name = " ".join(w for w in t[i:] if w not in _FILLER)
        if unit == "100g":
            qty, unit = qty / 100, "100g"
        if not name:                                                       # just a quantity: it belongs to the food before it
            if out and (qty != 1 or unit):
                out[-1] = (qty, unit or out[-1][1], out[-1][2])
            continue
        out.append((qty, unit, name))
    return out


# ---------------------------------------------------------------- pricing
def _candidates(name, c):
    """Prices to try for a food, best first: the seed table's exact name, what was learned, then the longest table name that ends it."""
    key, out = norm(name), []
    if key in TABLE:
        out.append(TABLE[key])
    if c is not None:
        out += [(r["unit"], r["kcal"]) for r in c.execute("SELECT unit, kcal FROM foods WHERE name=?", (key,))]
    # a known food that ends the name: "grilled paneer" is paneer, but "chilli paneer momo" is not
    inside = sorted((k for k in TABLE if k != key and (key.endswith(" " + k))), key=len, reverse=True)
    return out + [TABLE[k] for k in inside]


def _price(qty, unit, per):
    table_unit, kcal = per
    share = 1 if unit in (None, table_unit) else _EQUIV.get((unit, table_unit))
    return None if share is None else qty * share * kcal


def estimate(text, c=None):
    """(calories of the foods the tables can price, foods they cannot as [(qty, unit, name)]). Calories are None when no food was found."""
    foods = parse(text)
    if not foods:
        return None, []
    total, unknown = 0.0, []
    for qty, unit, name in foods:
        got = next((k for k in (_price(qty, unit, per) for per in _candidates(name, c)) if k is not None), None)
        if got is None:
            unknown.append((qty, unit, name))
        else:
            total += got
    return round(total), unknown


# ---------------------------------------------------------------- foods a person does not want
_STOP = {"food", "meal", "lunch", "dinner", "breakfast", "snack", "anything", "everything", "it", "this", "that", "them", "those",
         "these", "same", "thing", "item", "stuff", "one", "dish", "option", "recipe", "much", "more", "any", "some", "the", "my",
         "a", "an", "too", "so", "very", "really", "anymore", "again", "ever", "please", "today", "tonight", "tomorrow", "now", "me"}


def clean_avoid(raw):
    """Short lowercase singular food names, no duplicates, at most MAX_AVOID."""
    out = []
    for t in raw if isinstance(raw, list) else []:
        words = [w for w in map(singular, re.findall(r"[a-z]+", str(t).lower())) if w not in _STOP]
        term = " ".join(words[:3])
        if 2 <= len(term) <= 30 and term not in out:
            out.append(term)
    return out[:MAX_AVOID]


def mentions(text, terms):
    """True when text contains any of the foods, matching from the start of a word: "bhakri" also matches "bhakris"."""
    t = str(text).lower()
    return any(re.search(r"(?:^|[^a-z])" + re.escape(x), t) for x in terms)


_ONE_OFF = re.compile(r"\b(today|tonight|tomorrow|now|this time|this meal|just this|only today)\b")
_DISLIKE = re.compile(
    r"\b(?:(?:don'?t|do not|didn'?t|doesn'?t|never|can'?t|cannot|won'?t)\s+(?:really\s+|ever\s+)?"
    r"(?:like|eat|want|enjoy|stand|touch|give|serve|suggest|recommend|show)(?:\s+me)?"
    r"|hate|dislike|detest|allergic to|allergy to|intolerant to|sick of|tired of|bored of|fed up with|no more"
    r"|stop (?:giving|suggesting|recommending|showing|serving)(?:\s+me)?)\s+(.+)")


def avoid_from_text(text):
    """Foods a plain request says the person dislikes for good, like "I don't like bhakri and paneer". Only names it
    recognises as food are kept; the AI reads the rest. One-off wishes ("no paneer today") are ignored."""
    t = str(text).lower()
    m = _DISLIKE.search(t)
    if not m or _ONE_OFF.search(t):
        return []
    rest = re.split(r"[.!?\n]|\b(?:but|because|since|as|so|it|when|if|at|in|on|for|to)\b", m.group(1))[0]
    pieces = re.split(r",|&|/|\+|\band\b|\bor\b", rest)
    return clean_avoid([p for p in pieces if any(singular(w) in FOOD_WORDS for w in re.findall(r"[a-z]+", p))])[:5]
