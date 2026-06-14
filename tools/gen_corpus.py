#!/usr/bin/env python3
"""Generate a deterministic synthetic note corpus + ground-truth queries for scale testing.

Writes:
  app/src/androidTest/assets/corpus.json   ~300 notes: {daysAgo, topic, tags, text}
  app/src/androidTest/assets/queries.json  ground truth: factual (expectContains) + topic (precision@k)

Deterministic (fixed seed) so the corpus and its known facts never drift between runs.
Grocery notes carry a Markdown checklist (- [x] bought / - [ ] needed) so the phase-3 complex
queries (checklist-aware + time-window) have assertable ground truth; other topics stay plain.
"""
import json
import os
import random

R = random.Random(42)
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "androidTest", "assets")

notes = []  # each: {daysAgo, topic, tags, text}


def add(days_ago, topic, tags, text, markdown=""):
    notes.append({"daysAgo": days_ago, "topic": topic, "tags": tags, "text": text, "markdown": markdown})


# ---- recurring topics spread across ~6 months (0..180 days ago) ----

# Groceries — Markdown checklists ("- [x]" bought / "- [ ]" still needed). The two forced runs
# below are the only grocery notes within the last 7 days; random runs start >7 days ago. That
# makes the "last week" window deterministic ground truth for the phase-3 checklist+time queries.
GROCERIES = [
    "milk", "eggs", "bread", "butter", "cheese", "apples", "bananas", "chicken",
    "rice", "pasta", "tomatoes", "onions", "coffee", "yogurt", "spinach", "cereal",
    "olive oil", "salmon", "potatoes", "carrots", "oranges", "flour", "sugar", "tea",
]


def grocery_text(items, bought):
    return "Grocery run. " + ", ".join(f"got {x}" if x in bought else f"need {x}" for x in items) + "."


def grocery_md(items, bought):
    return "Grocery run\n" + "\n".join(f"- [x] {x}" if x in bought else f"- [ ] {x}" for x in items)


grocery_recent = []  # (bought, needed) for runs within the last 7 days — ground-truth source

# Forced recent runs (within the last week) — deterministic ground truth.
for days, items, bought in [
    (2, ["milk", "eggs", "bread", "coffee", "rice"], ["milk", "eggs"]),
    (5, ["bananas", "yogurt", "spinach", "chicken"], ["bananas", "yogurt"]),
]:
    add(days, "groceries", ["shopping", "groceries"], grocery_text(items, bought), grocery_md(items, bought))
    grocery_recent.append((bought, [x for x in items if x not in bought]))

# An older run that bought items we never buy in the last week — time-filter discriminator.
add(120, "groceries", ["shopping", "groceries"],
    grocery_text(["olive oil", "salmon", "sugar"], ["olive oil", "salmon"]),
    grocery_md(["olive oil", "salmon", "sugar"], ["olive oil", "salmon"]))

for i in range(34):
    days = i * 5 + 8 + R.randint(0, 3)   # all >7 days ago
    n = R.randint(4, 7)
    items = R.sample(GROCERIES, n)
    bought = R.sample(items, R.randint(0, n))
    add(days, "groceries", ["shopping", "groceries"], grocery_text(items, bought), grocery_md(items, bought))

# Work project "Atlas" — standups, decisions, todos.
ATLAS = [
    "Standup: finished the auth refactor, blocked on the staging deploy.",
    "Decided to move the Atlas API to gRPC next quarter. Need to spike load testing.",
    "Atlas bug: the export job times out on large tenants. Add pagination.",
    "Code review backlog is growing. Propose a rotation in the team sync.",
    "Atlas roadmap: ship search v2, then the billing migration.",
    "Postmortem for the Atlas outage: root cause was a missing index on events.",
    "Sprint planning: pull the analytics dashboard into this cycle.",
    "Pairing with Sam on the Atlas cache layer. Redis vs in-memory still open.",
    "Atlas: customer asked for SSO. Scope SAML for next month.",
    "Refactor the Atlas ingestion pipeline; the queue keeps backing up.",
]
for i in range(40):
    days = R.randint(0, 180)
    add(days, "work", ["work", "atlas"], R.choice(ATLAS))

# Health — dentist, gym, doctor. PLANTED FACT: dentist appointment on March 3.
add(12, "health", ["health", "dentist"], "Dentist appointment booked for March 3 at 9am. Cleaning plus that filling.")
HEALTH = [
    "Gym: leg day. Squats felt strong, added 5kg.",
    "Doctor said the bloodwork looks fine, recheck in six months.",
    "Started running again, 5k three times this week. Knee held up.",
    "Need to refill the allergy prescription before it runs out.",
    "Physio for the shoulder, do the band exercises daily.",
    "Slept badly all week. Cut coffee after 2pm and it helped.",
    "Flu shot done at the pharmacy.",
]
for i in range(32):
    days = R.randint(0, 180)
    add(days, "health", ["health"], R.choice(HEALTH))

# Travel
TRAVEL = [
    "Trip to Lisbon: book flights, find an apartment in Alfama.",
    "Portugal itinerary: Lisbon three days, then train to Porto.",
    "Pack list for the hiking trip: boots, rain jacket, water filter.",
    "Kyoto in autumn — temples, then a day trip to Nara for the deer.",
    "Road trip route: coast highway, stop at the redwoods overnight.",
    "Renew passport before the Japan trip, it expires soon.",
    "Found cheap flights to Reykjavik for the northern lights week.",
]
for i in range(30):
    days = R.randint(0, 180)
    add(days, "travel", ["travel"], R.choice(TRAVEL))

# Finance. PLANTED FACT: Netflix subscription $15.99.
add(40, "finance", ["finance", "subscriptions"], "Audited subscriptions: Netflix is 15.99 a month, Spotify 9.99. Cancel the unused gym app.")
FINANCE = [
    "Monthly budget: rent, groceries, transit, savings. Stay under target.",
    "Moved the emergency fund into a high-yield account.",
    "Quarterly taxes due soon, set aside the estimate.",
    "Electricity bill spiked this month, check the heating schedule.",
    "Rebalanced the index fund allocation, more bonds.",
]
for i in range(28):
    days = R.randint(0, 180)
    add(days, "finance", ["finance"], R.choice(FINANCE))

# Learning Python — concentrated in one ~month window (days 30..60).
PY = [
    "Python: decorators are just functions returning functions. Wrote a timing decorator.",
    "asyncio notes: await yields control to the event loop; gather runs coroutines concurrently.",
    "Learned about Python generators and lazy evaluation with yield.",
    "Type hints + mypy caught a real bug today. Worth the friction.",
    "Context managers with __enter__/__exit__, and contextlib.contextmanager.",
    "dataclasses vs namedtuple vs pydantic — pydantic for validation.",
    "Python packaging is still a mess; settled on uv + pyproject.toml.",
]
for i in range(32):
    days = 30 + R.randint(0, 30)
    add(days, "python", ["learning", "python"], R.choice(PY))

# Learning Rust — a different ~month window (days 80..120).
RUST = [
    "Rust: the borrow checker is strict but the errors are teaching me ownership.",
    "Ownership and moves in Rust: values have one owner, borrows are references.",
    "Lifetimes finally clicked — they annotate how long references stay valid.",
    "Rust traits are like interfaces; impl blocks add methods.",
    "Pattern matching with match and enums is so clean in Rust.",
    "Cargo and crates.io make the Rust toolchain pleasant.",
    "Fighting the borrow checker on a graph; might use Rc<RefCell<>>.",
]
for i in range(32):
    days = 80 + R.randint(0, 40)
    add(days, "rust", ["learning", "rust"], R.choice(RUST))

# Home / DIY. PLANTED FACT: wifi password bluefox42.
add(70, "home", ["home", "diy"], "Set up the new mesh router. The wifi password is bluefox42. Coverage is great now.")
HOME = [
    "Fix the leaky kitchen sink — replace the washer, get the right size.",
    "Painted the spare room. Two coats of the off-white.",
    "Assembled the IKEA shelves, missing two dowels, ordered replacements.",
    "Clean the gutters before the rainy season.",
    "The dryer is squeaking; probably the belt. Order the part.",
    "Hung the shelves in the hallway, finally.",
]
for i in range(30):
    days = R.randint(0, 180)
    add(days, "home", ["home"], R.choice(HOME))

# Family. PLANTED FACT: Mom's birthday April 12.
add(20, "family", ["family"], "Mom's birthday is April 12. Plan a dinner and get her the gardening book.")
FAMILY = [
    "Call grandma this weekend.",
    "Kids' parent-teacher conference next Thursday.",
    "Pick up a birthday present for my niece.",
    "Family dinner on Sunday, call everyone to confirm.",
    "Help Dad set up his new phone.",
]
for i in range(26):
    days = R.randint(0, 180)
    add(days, "family", ["family"], R.choice(FAMILY))

# Ideas / musings. PLANTED FACT: reading 'The Pragmatic Programmer'.
add(15, "ideas", ["ideas", "books"], "Reading 'The Pragmatic Programmer'. The bit on DRY and orthogonality is gold.")
IDEAS = [
    "App idea: a privacy-first voice notes app that transcribes on-device.",
    "Idea: a CLI that summarizes my git history into a changelog.",
    "What if notes could auto-link by topic? Embeddings could do it.",
    "Musing: I focus better in the morning; protect that block.",
    "Idea: a tiny e-ink dashboard for the week's tasks.",
    "Should learn more about vector databases for the notes idea.",
]
for i in range(26):
    days = R.randint(0, 180)
    add(days, "ideas", ["ideas"], R.choice(IDEAS))

notes.sort(key=lambda n: -n["daysAgo"])  # oldest first

queries = {
    "factual": [
        {"q": "What is my wifi password?", "expectContains": ["bluefox42"]},
        {"q": "When is my dentist appointment?", "expectContains": ["March 3", "march 3"]},
        {"q": "When is mom's birthday?", "expectContains": ["April 12", "april 12"]},
        {"q": "How much is my Netflix subscription?", "expectContains": ["15.99"]},
        {"q": "What book am I reading?", "expectContains": ["Pragmatic Programmer"]},
    ],
    # topic: top-k search results should mostly be the expected topic.
    "topic": [
        {"q": "what groceries do I need to buy", "topic": "groceries", "k": 5, "minPrecision": 0.6},
        {"q": "rust borrow checker and ownership", "topic": "rust", "k": 5, "minPrecision": 0.6},
        {"q": "python async and decorators", "topic": "python", "k": 5, "minPrecision": 0.6},
        {"q": "home repairs and fixing things", "topic": "home", "k": 5, "minPrecision": 0.5},
        {"q": "planning a vacation trip", "topic": "travel", "k": 5, "minPrecision": 0.6},
        {"q": "the Atlas project at work", "topic": "work", "k": 5, "minPrecision": 0.6},
    ],
    # complex: checklist-aware + time-window + topic-synthesis queries over the whole corpus,
    # answered via RAG (NotesAnalysis.ask reads the Markdown checklists + per-note dates).
    "complex": [
        {
            "q": "Which groceries did I buy in the last week?",
            "kind": "all_and_none",
            "expect": sorted({x for bought, _ in grocery_recent for x in bought}),
            "absent": ["olive oil", "salmon"],  # bought 120 days ago, not last week
            "note": "checked (- [x]) items in grocery notes from the last 7 days",
        },
        {
            "q": "Which groceries do I still need to buy this week?",
            "kind": "all_and_none",
            "expect": sorted({x for _, needed in grocery_recent for x in needed}),
            "absent": ["olive oil", "salmon"],  # bought (- [x]) long ago — never "still need"
            "note": "unchecked (- [ ]) items in recent grocery notes",
        },
        {
            "q": "What have I been learning about Rust?",
            "kind": "contains_any",
            "min": 3,
            "expect": ["borrow", "ownership", "lifetime", "trait", "cargo", "pattern", "enum"],
            "note": "multi-note topic synthesis",
        },
    ],
}

os.makedirs(OUT, exist_ok=True)
with open(os.path.join(OUT, "corpus.json"), "w") as f:
    json.dump(notes, f, indent=0)
with open(os.path.join(OUT, "queries.json"), "w") as f:
    json.dump(queries, f, indent=2)

print(f"wrote {len(notes)} notes across {len(set(n['topic'] for n in notes))} topics")
print("topics:", {t: sum(1 for n in notes if n["topic"] == t) for t in sorted(set(n["topic"] for n in notes))})
