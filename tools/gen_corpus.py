#!/usr/bin/env python3
"""Generate a deterministic synthetic note corpus + ground-truth queries for scale testing.

Writes:
  app/src/androidTest/assets/corpus.json   ~300 notes: {daysAgo, topic, tags, text}
  app/src/androidTest/assets/queries.json  ground truth: factual (expectContains) + topic (precision@k)

Deterministic (fixed seed) so the corpus and its known facts never drift between runs.
Phase 1 is plain text (no markdown yet — that's phase 2); checklists here are prose.
"""
import json
import os
import random

R = random.Random(42)
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "androidTest", "assets")

notes = []  # each: {daysAgo, topic, tags, text}


def add(days_ago, topic, tags, text):
    notes.append({"daysAgo": days_ago, "topic": topic, "tags": tags, "text": text})


# ---- recurring topics spread across ~6 months (0..180 days ago) ----

# Groceries — roughly weekly, varied item lists (some "bought", some "need").
GROCERIES = [
    "milk", "eggs", "bread", "butter", "cheese", "apples", "bananas", "chicken",
    "rice", "pasta", "tomatoes", "onions", "coffee", "yogurt", "spinach", "cereal",
    "olive oil", "salmon", "potatoes", "carrots", "oranges", "flour", "sugar", "tea",
]
for i in range(34):
    days = i * 5 + R.randint(0, 3)
    n = R.randint(4, 7)
    items = R.sample(GROCERIES, n)
    bought = R.sample(items, R.randint(0, n))
    parts = [f"need {x}" if x not in bought else f"got {x}" for x in items]
    add(days, "groceries", ["shopping", "groceries"],
        "Grocery run. " + ", ".join(parts) + ".")

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
}

os.makedirs(OUT, exist_ok=True)
with open(os.path.join(OUT, "corpus.json"), "w") as f:
    json.dump(notes, f, indent=0)
with open(os.path.join(OUT, "queries.json"), "w") as f:
    json.dump(queries, f, indent=2)

print(f"wrote {len(notes)} notes across {len(set(n['topic'] for n in notes))} topics")
print("topics:", {t: sum(1 for n in notes if n["topic"] == t) for t in sorted(set(n["topic"] for n in notes))})
