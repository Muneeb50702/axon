"""
Builds the AXON FYP Proposal Defence presentation.

Starts from the university template so the master, theme, logo, footers and
slide numbering are preserved exactly, then fills each slide with project
content. Structure and slide headings are NOT changed — the department's
instructions say the main headings are part of the required format.
"""
from pptx import Presentation
from pptx.util import Inches, Pt, Emu
from pptx.dml.color import RGBColor
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
from pptx.enum.shapes import MSO_SHAPE
import copy

TEMPLATE = "FYP_Proposals_Defense_Presentation_Template_SuperiorUniversity.pptx"
OUT = "ppt/Room 1-8-Dr. Saleem Mustafa.pptx"

# Palette taken from the template/example so the deck stays on-brand.
BLUE   = RGBColor(0x15, 0x60, 0x82)
PURPLE = RGBColor(0xA0, 0x2B, 0x93)
GREEN  = RGBColor(0x1E, 0x7A, 0x5E)
AMBER  = RGBColor(0xB4, 0x6E, 0x00)
GREY   = RGBColor(0x8C, 0x8C, 0x8C)
WHITE  = RGBColor(0xFF, 0xFF, 0xFF)
INK    = RGBColor(0x1F, 0x1F, 0x1F)
SHADE  = RGBColor(0xC0, 0xE4, 0xF5)
SHADE2 = RGBColor(0xE8, 0xD4, 0xEF)

prs = Presentation(TEMPLATE)


# --------------------------------------------------------------------------
# helpers
# --------------------------------------------------------------------------
def body_of(slide):
    for sh in slide.shapes:
        if sh.is_placeholder and sh.placeholder_format.idx == 1:
            return sh
    return None


def set_title(slide, text, size=32):
    for sh in slide.shapes:
        if sh.is_placeholder and sh.placeholder_format.idx == 0:
            tf = sh.text_frame
            tf.text = text
            for p in tf.paragraphs:
                for r in p.runs:
                    r.font.size = Pt(size)
                    r.font.bold = True
            return sh
    return None


def fill_body(shape, items, base=18, tight=False):
    """items: list of (level, text, bold) — level 0 = heading bullet."""
    tf = shape.text_frame
    tf.clear()
    tf.word_wrap = True
    first = True
    for level, text, bold in items:
        p = tf.paragraphs[0] if first else tf.add_paragraph()
        first = False
        p.level = level
        r = p.add_run()
        r.text = text
        r.font.size = Pt(base if level == 0 else base - 2)
        r.font.bold = bold
        r.font.color.rgb = BLUE if bold and level == 0 else INK
        p.space_after = Pt(3 if tight else 6)
    return tf


def box(slide, x, y, w, h, text, sub, fill, font=11, subfont=9):
    s = slide.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE,
                               Inches(x), Inches(y), Inches(w), Inches(h))
    s.fill.solid()
    s.fill.fore_color.rgb = fill
    s.line.fill.background()
    s.shadow.inherit = False
    tf = s.text_frame
    tf.word_wrap = True
    tf.margin_left = tf.margin_right = Inches(0.06)
    tf.margin_top = tf.margin_bottom = Inches(0.03)
    tf.vertical_anchor = MSO_ANCHOR.MIDDLE
    p = tf.paragraphs[0]
    p.alignment = PP_ALIGN.CENTER
    r = p.add_run(); r.text = text
    r.font.size = Pt(font); r.font.bold = True; r.font.color.rgb = WHITE
    if sub:
        p2 = tf.add_paragraph(); p2.alignment = PP_ALIGN.CENTER
        r2 = p2.add_run(); r2.text = sub
        r2.font.size = Pt(subfont); r2.font.color.rgb = WHITE
    return s


def arrow(slide, x, y, w=0.30, h=0.26, shape=MSO_SHAPE.RIGHT_ARROW):
    a = slide.shapes.add_shape(shape, Inches(x), Inches(y), Inches(w), Inches(h))
    a.fill.solid(); a.fill.fore_color.rgb = GREY
    a.line.fill.background(); a.shadow.inherit = False
    return a


def label(slide, x, y, w, h, text, size=11, bold=False, color=INK, align=PP_ALIGN.LEFT):
    tb = slide.shapes.add_textbox(Inches(x), Inches(y), Inches(w), Inches(h))
    tf = tb.text_frame; tf.word_wrap = True
    p = tf.paragraphs[0]; p.alignment = align
    r = p.add_run(); r.text = text
    r.font.size = Pt(size); r.font.bold = bold; r.font.color.rgb = color
    return tb


def drop_slide(index):
    lst = prs.slides._sldIdLst
    lst.remove(list(lst)[index])


def move_slide(old, new):
    lst = prs.slides._sldIdLst
    ids = list(lst)
    lst.remove(ids[old])
    lst.insert(new, ids[old])


def clone_slide(index):
    """Duplicate an existing slide (keeps its layout + logo + footers)."""
    src = prs.slides[index]
    dest = prs.slides.add_slide(src.slide_layout)
    for sh in list(dest.shapes):
        sh._element.getparent().remove(sh._element)
    for sh in src.shapes:
        dest.shapes._spTree.append(copy.deepcopy(sh._element))
    return dest


# ==========================================================================
# SLIDE 1 — Title
# ==========================================================================
s1 = prs.slides[0]
for sh in s1.shapes:
    if sh.name == "CustomShape 1":
        tf = sh.text_frame; tf.clear(); tf.word_wrap = True
        p = tf.paragraphs[0]; p.alignment = PP_ALIGN.CENTER
        r = p.add_run()
        r.text = "Project AXON"
        r.font.size = Pt(40); r.font.bold = True
        p2 = tf.add_paragraph(); p2.alignment = PP_ALIGN.CENTER
        r2 = p2.add_run()
        r2.text = ("An Offline, Self-Healing On-Device AI Agent for Android\n"
                   "with Learned Skill Compilation")
        r2.font.size = Pt(18); r2.font.bold = False
    if sh.has_table:
        t = sh.table
        rows = [
            ("Group / BSCS-J", "", "STUDENT NAME", "SAP ID"),
            ("", "", "Muhammad Muneeb  (Group Leader)", "SU92-BSCSM-F23-___"),
            ("", "", "Nasar Fareed", "SU92-BSCSM-F23-___"),
            ("", "", "Danish Ali", "SU92-BSCSM-F23-___"),
            ("Supervisor", "", "Dr. Saleem Mustafa", "Group ID: CS-025"),
        ]
        for ri, vals in enumerate(rows):
            for ci, v in enumerate(vals):
                cell = t.cell(ri, ci)
                cell.text = v
                for p in cell.text_frame.paragraphs:
                    for r in p.runs:
                        r.font.size = Pt(13)
                        r.font.bold = (ri == 0 or ci == 0 or ri == 4)

# ==========================================================================
# SLIDE 2 — Introduction
# ==========================================================================
s2 = prs.slides[1]
set_title(s2, "Introduction")
fill_body(body_of(s2), [
    (0, "Two billion people use mid-range Android phones on slow, costly or "
        "intermittent internet — every AI assistant today sends their screen to a cloud server.", True),
    (0, "AXON runs a 1-billion-parameter AI model entirely on the phone.", True),
    (1, "No internet. No cloud API. No per-message cost. The app has no network permission at all.", False),
    (0, "It operates the phone the way a person does — reads the screen, then taps.", True),
    (1, "Send a message, set an alarm, change a setting, navigate — by natural-language request.", False),
    (0, "And it gets faster the more it is used.", True),
    (1, "Once AXON has learned a task, it repeats it with ZERO AI calls — measured at 8,407× faster.", False),
], base=19)

# ==========================================================================
# SLIDE 3 — Background
# ==========================================================================
s3 = prs.slides[2]
set_title(s3, "Background")
for sh in list(s3.shapes):
    if sh.shape_type is not None and sh.has_chart:
        sh._element.getparent().remove(sh._element)

b3 = body_of(s3)
b3.left, b3.top = Inches(0.55), Inches(1.95)
b3.width, b3.height = Inches(5.9), Inches(4.6)
fill_body(b3, [
    (0, "Why phone AI is cloud-based today", True),
    (1, "Understanding a screen and planning multi-step actions has needed large models.", False),
    (1, "Large models do not fit on a phone, so the screen goes to a server.", False),
    (0, "What changed in 2025–2026", True),
    (1, "Small models (Gemma, Phi, Qwen) at 1–4B now run usefully on mid-range phones.", False),
    (1, "Constrained decoding (GBNF grammars) can force a model's output to be valid by construction.", False),
    (1, "Android exposes a structured UI tree — machine-readable text, not screenshots.", False),
], base=16)

label(s3, 6.75, 1.95, 5.9, 0.4, "The gap that remains", 18, True, BLUE)
box(s3, 6.75, 2.45, 5.85, 1.15,
    "A 1B model is small enough to fit — but not reliable enough to trust",
    "It invents buttons that do not exist, and misjudges whether an action worked.",
    AMBER, 13, 11)
box(s3, 6.75, 3.80, 5.85, 1.15,
    "AXON's thesis: reliability is an ARCHITECTURE problem",
    "Do not make the model smarter. Make the model's freedom smaller.",
    BLUE, 13, 11)
box(s3, 6.75, 5.15, 5.85, 1.15,
    "Target device: TECNO Camon 20 (~PKR 40,000)",
    "MediaTek Helio G85 · 8 GB RAM — not a flagship, on purpose.",
    GREEN, 13, 11)

# ==========================================================================
# SLIDE 4 — Problem Statement
# ==========================================================================
s4 = prs.slides[3]
set_title(s4, "Problem Statement")
fill_body(body_of(s4), [
    (0, "Problem", True),
    (1, "A small on-device model asked to operate a phone hallucinates: it names buttons that are "
        "not on screen, believes failed actions succeeded, and derails by step 4 of a 6-step task.", False),
    (1, "Cloud agents are accurate but structurally unavailable — they need constant connectivity, "
        "cost money per action, and upload private screens (banking, health, chats) to third-party servers.", False),
    (0, "Why solving it matters", True),
    (1, "Privacy by construction: screen contents that never leave the device cannot be leaked.", False),
    (1, "Access: works where data is expensive or unreliable — and costs nothing per use.", False),
    (1, "Assistive value: a voice-driven bridge for low-literacy and low-vision users who can speak "
        "a request but cannot navigate a complex UI.", False),
    (0, "Impact if successful", True),
    (1, "Demonstrates that a 1B offline model can be made reliable through architecture — turning "
        "\"small models are unreliable\" into a measured, defeated claim.", False),
], base=16)

# ==========================================================================
# SLIDE 5 — Aims & Objectives
# ==========================================================================
s5 = prs.slides[4]
set_title(s5, "Aims & Objectives")
fill_body(body_of(s5), [
    (0, "Objective 1 — Constrained on-device action generation", True),
    (1, "Run a Q4-quantized ≤2B model fully offline; 100% structurally valid actions across ≥500 generations.", False),
    (0, "Objective 2 — Safe execution", True),
    (1, "Planner–executor split with a precondition gate that rejects impossible actions "
        "BEFORE the device is touched.", False),
    (0, "Objective 3 — Deterministic verification and self-healing", True),
    (1, "Verify every action by UI-tree diff (no AI judging its own work); recover ≥60% of injected failures.", False),
    (0, "Objective 4 — Learned skill compilation", True),
    (1, "Compile a verified task trace into a replayable skill: 0 AI calls on repeat, ≥5× faster than a cold run.", False),
    (0, "Objective 5 — Rigorous evaluation", True),
    (1, "AXON-Bench: multi-step task benchmark with an ablation matrix and reference baselines.", False),
], base=15, tight=True)

# ==========================================================================
# SLIDE 6 — Literature Study
# ==========================================================================
s6 = prs.slides[5]
set_title(s6, "Literature Study")
b6 = body_of(s6)
b6.top, b6.height = Inches(1.80), Inches(1.35)
fill_body(b6, [
    (0, "Three existing approaches — and what each one lacks", True),
], base=17)

hdr = [("Approach", 0.92, 2.4), ("Examples", 4.5, 2.3), ("Limitation for our problem", 6.9, 5.5)]
for txt, x, w in hdr:
    label(s6, x, 3.05, w, 0.32, txt, 13, True, BLUE)

rows = [
    ("Vision-language screenshot loops", "AutoGLM, Roubao [3]",
     "Slow and non-deterministic; re-solves every task from scratch; needs a large model."),
    ("Hand-written tool runners", "Mythara, Operit AI [4]",
     "Tool library is static and hand-maintained; reasoning quality depends on model size."),
    ("Trace-to-skill compilation", "SkillDroid, 2026 [2]",
     "Closest work — but calls CLOUD GPT models, so it never faces the malformed-output problem."),
]
y = 3.45
for a, b, c in rows:
    label(s6, 0.92, y, 3.4, 0.62, a, 12, True)
    label(s6, 4.5, y, 2.3, 0.62, b, 12)
    label(s6, 6.9, y, 5.5, 0.62, c, 12)
    y += 0.78

gap = s6.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE,
                          Inches(0.92), Inches(5.85), Inches(11.5), Inches(0.95))
gap.fill.solid(); gap.fill.fore_color.rgb = BLUE
gap.line.fill.background(); gap.shadow.inherit = False
tfg = gap.text_frame; tfg.word_wrap = True
tfg.vertical_anchor = MSO_ANCHOR.MIDDLE
pg = tfg.paragraphs[0]; pg.alignment = PP_ALIGN.CENTER
rg = pg.add_run()
rg.text = ("RESEARCH GAP  —  No existing system makes a sub-2B, fully offline model reliable enough "
           "to drive a phone.\nCloud-scale models do not have the problem we are solving.")
rg.font.size = Pt(14); rg.font.bold = True; rg.font.color.rgb = WHITE

# ==========================================================================
# SLIDE 7 — Methodology
# ==========================================================================
s7 = prs.slides[6]
set_title(s7, "Methodology")
b7 = body_of(s7)
b7.top, b7.height = Inches(1.78), Inches(1.15)
fill_body(b7, [
    (0, "Make the model's freedom smaller: three structural defences, then learn from success.", True),
], base=16)

defs = [
    ("1 · Grammar-constrained generation", "Malformed actions are UNREACHABLE at the sampler — not rejected afterwards.", BLUE),
    ("2 · Precondition gate", "An action naming an element not on screen is refused before the device is touched.", GREEN),
    ("3 · Deterministic verifier", "UI-tree diff decides success — the AI never judges its own work.", PURPLE),
    ("4 · Skill compilation", "A verified trace is frozen into a replayable script — future runs cost 0 AI calls.", AMBER),
]
x = 0.92
for t, sub, col in defs:
    box(s7, x, 2.95, 2.78, 1.35, t, sub, col, 12, 10)
    x += 2.90

label(s7, 0.92, 4.55, 11.5, 0.35, "Tools & technologies", 15, True, BLUE)
fb = s7.shapes.add_textbox(Inches(0.92), Inches(4.92), Inches(11.5), Inches(1.9))
tf = fb.text_frame; tf.word_wrap = True
for i, line in enumerate([
    "Language / core:  Kotlin Multiplatform  —  portable reliability core with a swappable OS driver layer",
    "Inference:  llama.cpp via our own JNI bridge (GGUF, Q4_K_M)  ·  Model: Gemma 3 1B  ·  GBNF constrained decoding",
    "Device control:  Android AccessibilityService (UI tree + gestures)  ·  UI: Jetpack Compose  ·  Foreground-service gateway",
    "Evaluation:  AXON-Bench — task success, valid-action rate, AI calls/task, latency, energy per task",
]):
    p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
    r = p.add_run(); r.text = line
    r.font.size = Pt(13)

# ==========================================================================
# SLIDE 8 — Proposed Design Block Diagram
# ==========================================================================
s8 = prs.slides[7]
set_title(s8, "Proposed Design Block Diagram")
for sh in list(s8.shapes):
    if sh.is_placeholder and sh.placeholder_format.idx == 1:
        sh._element.getparent().remove(sh._element)
    elif sh.name == "TextBox 2":
        sh._element.getparent().remove(sh._element)

label(s8, 0.55, 1.90, 12.0, 0.3, "USER GOAL   \"send on my way to Ammi on WhatsApp\"", 13, True, BLUE)

box(s8, 0.55, 2.24, 2.35, 0.72, "Skill Store", "Have we done this before?", GREY, 12, 9)
arrow(s8, 3.00, 2.46)
box(s8, 3.42, 2.24, 2.35, 0.72, "REPLAY path", "0 AI calls · 17 ms", PURPLE, 12, 9)
label(s8, 5.95, 2.34, 2.2, 0.5, "hit ➜ done", 11, True, PURPLE)

label(s8, 0.55, 3.08, 12.0, 0.28, "MISS ➜ PLAN path  (the cold, AI-driven route)", 12, True, INK)

row_y = 3.42
box(s8, 0.55, row_y, 2.20, 1.00, "1 · PERCEIVE", "Accessibility UI tree ➜ compact screen", BLUE, 11, 9)
arrow(s8, 2.85, row_y + 0.36)
box(s8, 3.25, row_y, 2.20, 1.00, "2 · PLAN", "1B model + GBNF grammar", BLUE, 11, 9)
arrow(s8, 5.55, row_y + 0.36)
box(s8, 5.95, row_y, 2.20, 1.00, "3 · GATE", "Reject impossible actions", GREEN, 11, 9)
arrow(s8, 8.25, row_y + 0.36)
box(s8, 8.65, row_y, 2.05, 1.00, "4 · ACT", "Dispatch gesture", GREEN, 11, 9)
arrow(s8, 10.80, row_y + 0.36)
box(s8, 11.20, row_y, 1.55, 1.00, "5 · VERIFY", "Tree diff", PURPLE, 11, 9)

label(s8, 3.25, 4.52, 6.0, 0.28, "◀ mismatch ➜ SELF-HEAL: re-plan with the failure injected", 11, True, AMBER)

row2 = 4.85
box(s8, 0.55, row2, 2.20, 0.85, "Trace Memory", "Every verified step recorded", GREY, 11, 9)
arrow(s8, 2.85, row2 + 0.28)
box(s8, 3.25, row2, 2.20, 0.85, "Skill Compiler", "Trace ➜ parameterised skill", AMBER, 11, 9)
arrow(s8, 5.55, row2 + 0.28)
box(s8, 5.95, row2, 2.20, 0.85, "New Skill Saved", "Next time: free", PURPLE, 11, 9)
arrow(s8, 8.25, row2 + 0.28, shape=MSO_SHAPE.UP_ARROW, w=0.26, h=0.85)
label(s8, 8.60, row2 + 0.10, 4.2, 0.6, "the learning loop closes —\nthe system gets faster with use", 11, True, PURPLE)

sf = s8.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE,
                         Inches(0.55), Inches(5.95), Inches(12.2), Inches(0.62))
sf.fill.solid(); sf.fill.fore_color.rgb = RGBColor(0xE9, 0xEF, 0xF4)
sf.line.color.rgb = BLUE; sf.shadow.inherit = False
tfs = sf.text_frame; tfs.vertical_anchor = MSO_ANCHOR.MIDDLE
ps = tfs.paragraphs[0]; ps.alignment = PP_ALIGN.CENTER
rs = ps.add_run()
rs.text = ("SAFETY LAYER (runs across every stage):  no INTERNET permission  ·  password / OTP fields "
           "never read  ·  visible foreground service  ·  irreversible actions need your approval")
rs.font.size = Pt(11); rs.font.bold = True; rs.font.color.rgb = BLUE

prs.save(OUT)
print("stage 1 saved:", OUT)


# ==========================================================================
# NEW SLIDE — Progress & Early Results   (inserted after the block diagram)
#
# The template allows extra slides. This is the deck's strongest card: at
# proposal stage the system already runs on real hardware with measured
# numbers, which almost no proposal defence can show.
# ==========================================================================
sr = clone_slide(5)                 # clone a Title-and-Content slide for its furniture
move_slide(len(prs.slides._sldIdLst) - 1, 8)
sr = prs.slides[8]

# Strip everything the clone inherited except the template furniture: title
# placeholder, footer, slide number and the university logo. Without this the
# new slide silently carries the source slide's content underneath ours.
for sh in list(sr.shapes):
    keep = (
        sh.name.startswith("Superior")
        or (sh.is_placeholder and sh.placeholder_format.idx in (0, 11, 12))
    )
    if not keep:
        sh._element.getparent().remove(sh._element)

set_title(sr, "Progress & Early Results")

label(sr, 0.92, 1.90, 11.5, 0.32,
      "Already implemented and measured on the target device — not a plan, a result.",
      15, True, BLUE)

# --- headline metric band -------------------------------------------------
cards = [
    ("8,407×", "faster on replay", "142,917 ms cold  ➜  17 ms replayed", PURPLE),
    ("0", "AI calls on replay", "target was \"fewer\"; achieved exactly zero", GREEN),
    ("100%", "valid actions", "vs 46.2% without our grammar", BLUE),
    ("83 J", "per planning step", "≈ 831 steps per full battery charge", AMBER),
]
x = 0.92
for big, mid, sub, col in cards:
    c = sr.shapes.add_shape(MSO_SHAPE.ROUNDED_RECTANGLE,
                            Inches(x), Inches(2.32), Inches(2.78), Inches(1.55))
    c.fill.solid(); c.fill.fore_color.rgb = col
    c.line.fill.background(); c.shadow.inherit = False
    tf = c.text_frame; tf.word_wrap = True
    tf.vertical_anchor = MSO_ANCHOR.MIDDLE
    p = tf.paragraphs[0]; p.alignment = PP_ALIGN.CENTER
    r = p.add_run(); r.text = big
    r.font.size = Pt(30); r.font.bold = True; r.font.color.rgb = WHITE
    p2 = tf.add_paragraph(); p2.alignment = PP_ALIGN.CENTER
    r2 = p2.add_run(); r2.text = mid
    r2.font.size = Pt(12); r2.font.bold = True; r2.font.color.rgb = WHITE
    p3 = tf.add_paragraph(); p3.alignment = PP_ALIGN.CENTER
    r3 = p3.add_run(); r3.text = sub
    r3.font.size = Pt(9); r3.font.color.rgb = WHITE
    x += 2.90

# --- what is built --------------------------------------------------------
label(sr, 0.92, 3.90, 5.6, 0.3, "Working today on the TECNO Camon 20", 14, True, BLUE)
built = [
    "On-device inference — Gemma 3 1B, fully offline, no network permission",
    "Grammar-constrained action generation (GBNF) — 100% valid",
    "Screen perception via AccessibilityService, with credential fields refused",
    "Precondition gate, deterministic verifier, self-healing loop",
    "Skill compiler + replay — the learning loop closes end to end",
    "Foreground-service gateway — operates other apps, stoppable by the user",
]
tb = sr.shapes.add_textbox(Inches(0.92), Inches(4.22), Inches(5.7), Inches(2.4))
tf = tb.text_frame; tf.word_wrap = True
for i, t in enumerate(built):
    p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
    r = p.add_run(); r.text = "✓  " + t
    r.font.size = Pt(11.5)
    p.space_after = Pt(3)

label(sr, 6.85, 3.90, 5.5, 0.3, "Engineering evidence", 14, True, BLUE)
ev = [
    ("129", "automated tests passing — 0 failures"),
    ("28", "documented design decisions, each with evidence"),
    ("18", "logged experiments with full provenance"),
    ("1", "experiment we VOIDED ourselves after finding our own baseline was unfair"),
]
tb2 = sr.shapes.add_textbox(Inches(6.85), Inches(4.22), Inches(5.6), Inches(1.95))
tf2 = tb2.text_frame; tf2.word_wrap = True
for i, (n, t) in enumerate(ev):
    p = tf2.paragraphs[0] if i == 0 else tf2.add_paragraph()
    r = p.add_run(); r.text = n + "   "
    r.font.size = Pt(15); r.font.bold = True; r.font.color.rgb = PURPLE
    r2 = p.add_run(); r2.text = t
    r2.font.size = Pt(11.5)
    p.space_after = Pt(6)

label(sr, 6.85, 6.32, 5.6, 0.38,
      "Half our evaluation runs with no phone attached — so results are reproducible by anyone.",
      10.5, True, GREEN)


# ==========================================================================
# SLIDE 10 — Project Timeline
# ==========================================================================
st = prs.slides[9]
set_title(st, "Project Timeline")
for sh in st.shapes:
    if sh.name == "TextBox 6":
        sh.top = Inches(1.90)
        tf = sh.text_frame; tf.clear()
        p = tf.paragraphs[0]
        r = p.add_run()
        r.text = ("Two-semester plan. Phases 0–5 are already complete (shaded darker) — the core "
                  "contributions are locked; the remaining work is evaluation and write-up.")
        r.font.size = Pt(12); r.font.bold = True; r.font.color.rgb = BLUE

# Eight tasks, matching the row count the template's table is sized for.
# Adding rows pushes the table past the footer line at 6.95 in.
TASKS = [
    ("Literature review & problem definition", 1, 2, True),
    ("System design — contracts, schemas, action grammar", 1, 2, True),
    ("On-device inference (llama.cpp JNI) + constrained decoding", 2, 3, True),
    ("Perception, executor & precondition gate", 3, 4, True),
    ("Planner in the loop + deterministic verifier / self-healing", 4, 5, True),
    ("Skill compiler & replay — the learning loop", 5, 6, True),
    ("AXON-Bench: benchmark, ablation matrix, results", 7, 9, False),
    ("Optimisation, user testing, thesis & defence", 9, 12, False),
]

for sh in st.shapes:
    if sh.has_table:
        sh.top, sh.height = Inches(2.62), Inches(3.75)
        t = sh.table
        # Template ships 10 rows; we need a header pair + 10 tasks.
        # python-pptx's row collection does not accept negative indices.
        while len(t.rows) < len(TASKS) + 2:
            last = t.rows[len(t.rows) - 1]
            t._tbl.append(copy.deepcopy(last._tr))
        for i, (name, start, end, done) in enumerate(TASKS):
            ri = i + 2
            t.cell(ri, 0).text = str(i + 1)
            t.cell(ri, 1).text = name
            for cell in (t.cell(ri, 0), t.cell(ri, 1)):
                for p in cell.text_frame.paragraphs:
                    for r in p.runs:
                        r.font.size = Pt(11)
            for m in range(1, 13):
                cell = t.cell(ri, m + 1)
                cell.text = ""
                if start <= m <= end:
                    cell.fill.solid()
                    cell.fill.fore_color.rgb = SHADE if done else SHADE2
                else:
                    cell.fill.background()

label(st, 0.92, 6.48, 11.5, 0.35,
      "■ shaded blue = completed   ■ shaded purple = remaining      "
      "Status at proposal stage: Phases 0–5 of 8 complete.",
      11, True, BLUE)


# ==========================================================================
# SLIDE 11 — References (IEEE)
# ==========================================================================
sref = prs.slides[10]
set_title(sref, "References")
REFS = [
    "[1] G. Gerganov et al., \"llama.cpp: LLM inference in C/C++,\" GitHub repository, 2026. [Online]. "
    "Available: https://github.com/ggml-org/llama.cpp",
    "[2] Y. Chen et al., \"SkillDroid: Compile Once, Reuse Forever — Skill Compilation for Mobile GUI Agents,\" "
    "arXiv:2604.14872, Apr. 2026.",
    "[3] Zhipu AI, \"AutoGLM: Autonomous Foundation Agents for GUIs,\" arXiv:2411.00820, 2024.",
    "[4] H. Wen et al., \"AutoDroid: LLM-powered Task Automation in Android,\" in Proc. ACM MobiCom, 2024.",
    "[5] Google DeepMind, \"Gemma 3: Lightweight Open Models for On-Device AI,\" Technical Report, 2025.",
    "[6] Google, \"AccessibilityService — Android Developers,\" 2026. [Online]. "
    "Available: https://developer.android.com/reference/android/accessibilityservice/AccessibilityService",
    "[7] B. Dong et al., \"XGrammar: Flexible and Efficient Structured Generation for LLMs,\" arXiv:2411.15100, 2024.",
    "[8] C. Rawles et al., \"AndroidWorld: A Dynamic Benchmarking Environment for Autonomous Agents,\" "
    "in Proc. ICLR, 2025.",
]
fill_body(body_of(sref), [(0, r, False) for r in REFS], base=12.5, tight=True)


# ==========================================================================
# Remove the template's instructional TIPS slide (last)
# ==========================================================================
drop_slide(len(prs.slides._sldIdLst) - 1)

prs.save(OUT)
print("saved:", OUT, "| slides:", len(prs.slides._sldIdLst))
