#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
slate_to_plug_verify.py

Verify, by pure geometry (no Minecraft, no Kotlin), the connectivity between the
UPSTREAM plain slate (Hex Casting 0.11.3
`at.petrak.hexcasting.common.blocks.circles.BlockSlate`) and this mod's double
slab (`PlugSlateBlock`), and audit the current
`PlugSlateBlock.canEnterFromDirection` axis-set criterion on that edge.

Units: 1/16 block. Cell-local coords 0..16.

PlugSlateBlock (verified against common/.../block/PlugSlateBlock.kt):
  HALF=BOTTOM -> plate (0,0,0)-(16,1,16);  HALF=TOP -> plate (0,15,0)-(16,16,16)
  wall 1px thick, glued to the FACING side; 1px is cut away on the plate side:
     BOTTOM y 1..16, TOP y 0..15
     WEST  (0,ya,0)-(1,yb,16)   EAST  (15,ya,0)-(16,yb,16)
     NORTH (0,ya,0)-(16,yb,1)   SOUTH (0,ya,15)-(16,yb,16)
  NOTE: FACING = the cell face the wall is glued to
        (exposed face normal = FACING.opposite).

BlockSlate (upstream 0.11.3, BlockSlate.java):
  FLOOR   (0,0,0)-(16,1,16)    normalDir = UP
  CEILING (0,15,0)-(16,16,16)  normalDir = DOWN
  WALL: FACING = *exposed face* normal (opposite semantics to PlugSlate)
     EAST  (0,0,0)-(1,16,16)    WEST  (15,0,0)-(16,16,16)
     SOUTH (0,0,0)-(16,16,1)    NORTH (0,0,15)-(16,16,16)
  So the same physical placement is written FACING=WEST by PlugSlate and
  FACING=EAST by BlockSlate.

phys definition (per task):
  source cell = this cell shifted by -16 along enterDir.
  Connected iff BOTH blocks have a piece reaching the shared boundary plane
  (that plane = the plug cell face in direction enterDir.opposite),
  and the two pieces' 2D closed-interval cross sections on that plane intersect
  (a degenerate edge or corner contact is allowed).

Outputs:
  doc/verify/slate_to_plug_states.png   all 6 slate + 8 plug states, 3D wireframe
  doc/verify/slate_to_plug_cases.png    12 decisive adjacency cases, 2 panels each
"""

import itertools
import os
import sys

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.lines import Line2D
from matplotlib.patches import Patch, Rectangle
from mpl_toolkits.mplot3d.art3d import Poly3DCollection

HERE = os.path.dirname(os.path.abspath(__file__))

# ----------------------------------------------------------------------------
# directions
# ----------------------------------------------------------------------------
DIRV = {
    "EAST": (1, 0, 0), "WEST": (-1, 0, 0),
    "UP": (0, 1, 0), "DOWN": (0, -1, 0),
    "SOUTH": (0, 0, 1), "NORTH": (0, 0, -1),
}
OPP = {"EAST": "WEST", "WEST": "EAST", "UP": "DOWN", "DOWN": "UP",
       "SOUTH": "NORTH", "NORTH": "SOUTH"}
AXIS = {"EAST": "X", "WEST": "X", "UP": "Y", "DOWN": "Y", "SOUTH": "Z", "NORTH": "Z"}
AXIDX = {"X": 0, "Y": 1, "Z": 2}
HORIZ = ["WEST", "EAST", "NORTH", "SOUTH"]
ALLDIRS = ["WEST", "EAST", "NORTH", "SOUTH", "UP", "DOWN"]

# colours: slate slab = teal, plug plate = steel blue, plug wall = orange
C_SLATE = "#3f8f7f"
C_PLATE = "#4c78a8"
C_WALL = "#e8894a"
C_CELL = "#9aa0a6"
C_HIT = "#2fae4a"


# ----------------------------------------------------------------------------
# pieces.  A box is [[x0,x1],[y0,y1],[z0,z1]] in 1/16 units.
# ----------------------------------------------------------------------------

def _box(x0, x1, y0, y1, z0, z1):
    return [[x0, x1], [y0, y1], [z0, z1]]


def plug_pieces(half, facing):
    """PlugSlateBlock: plate + wall."""
    py = (0, 1) if half == "BOTTOM" else (15, 16)
    plate = _box(0, 16, py[0], py[1], 0, 16)
    wy = (1, 16) if half == "BOTTOM" else (0, 15)
    if facing == "WEST":
        wall = _box(0, 1, wy[0], wy[1], 0, 16)
    elif facing == "EAST":
        wall = _box(15, 16, wy[0], wy[1], 0, 16)
    elif facing == "NORTH":
        wall = _box(0, 16, wy[0], wy[1], 0, 1)
    elif facing == "SOUTH":
        wall = _box(0, 16, wy[0], wy[1], 15, 16)
    else:
        raise ValueError(facing)
    return [("plate", plate), ("wall", wall)]


def slate_pieces(attach, facing):
    """Upstream BlockSlate: exactly one piece."""
    if attach == "FLOOR":
        return [("slab", _box(0, 16, 0, 1, 0, 16))]
    if attach == "CEILING":
        return [("slab", _box(0, 16, 15, 16, 0, 16))]
    # WALL: FACING = exposed face normal
    if facing == "EAST":
        return [("slab", _box(0, 1, 0, 16, 0, 16))]
    if facing == "WEST":
        return [("slab", _box(15, 16, 0, 16, 0, 16))]
    if facing == "SOUTH":
        return [("slab", _box(0, 16, 0, 16, 0, 1))]
    if facing == "NORTH":
        return [("slab", _box(0, 16, 0, 16, 15, 16))]
    raise ValueError(facing)


# ----------------------------------------------------------------------------
# geometry helpers
# ----------------------------------------------------------------------------

def shifted(box, d, amount=16):
    """Shift a box by -amount * unit(d)  (source cell sits at -16*enterDir)."""
    v = DIRV[d]
    out = [list(iv) for iv in box]
    for i in range(3):
        if v[i]:
            out[i][0] -= amount * v[i]
            out[i][1] -= amount * v[i]
    return out


def boundary_coord(d):
    """Coordinate (plug-cell frame) of the shared plane along enterDir.axis."""
    v = DIRV[d]
    return 0 if max(v) > 0 else 16


def touches(box, n, c):
    lo, hi = box[n]
    return lo <= c + 1e-9 and c - 1e-9 <= hi


def section(box, n):
    """2D closed-interval cross section on the plane perpendicular to axis n."""
    others = [i for i in range(3) if i != n]
    return (box[others[0]][0], box[others[0]][1], box[others[1]][0], box[others[1]][1])


def overlap2d(a, b):
    """Closed-rectangle intersection. Returns (u0,u1,v0,v1) or None."""
    u0, u1 = max(a[0], b[0]), min(a[1], b[1])
    v0, v1 = max(a[2], b[2]), min(a[3], b[3])
    if u0 > u1 + 1e-9 or v0 > v1 + 1e-9:
        return None
    return (u0, u1, v0, v1)


def is_degenerate(rect):
    return rect[1] - rect[0] <= 1e-9 or rect[3] - rect[2] <= 1e-9


def case_geometry(src, plug, d):
    """All boxes + boundary data for one (source, plug, enterDir) group."""
    n = AXIDX[AXIS[d]]
    c = boundary_coord(d)
    sb = [("src." + nm, shifted(bx, d)) for nm, bx in slate_pieces(src["attach"], src["facing"])]
    pb = [("plug." + nm, bx) for nm, bx in plug_pieces(plug["half"], plug["facing"])]
    return dict(n=n, c=c, src=sb, plug=pb)


def phys_check(src, plug, d):
    """Task's phys definition. Returns (bool, witness_text, area_px2, strict)."""
    g = case_geometry(src, plug, d)
    n, c = g["n"], g["c"]
    s_reach = [(nm, bx) for nm, bx in g["src"] if touches(bx, n, c)]
    p_reach = [(nm, bx) for nm, bx in g["plug"] if touches(bx, n, c)]
    best, best_area = None, -1.0
    for snm, sbx in s_reach:
        for pnm, pbx in p_reach:
            ov = overlap2d(section(sbx, n), section(pbx, n))
            if ov is None:
                continue
            area = (ov[1] - ov[0]) * (ov[3] - ov[2])
            txt = f"{snm} x {pnm} -> {ov}"
            if area > best_area:
                best, best_area = txt, area
    if best is not None:
        return True, best + f"  area={best_area:g}px2", best_area, best_area > 1e-9
    if not s_reach and not p_reach:
        why = "neither block reaches the shared plane"
    elif not s_reach:
        why = "source slab does not reach the shared plane"
    elif not p_reach:
        why = "no plug piece reaches the shared plane"
    else:
        why = "both reach the plane but the 2D sections are disjoint"
    return False, why, 0.0, False


# ----------------------------------------------------------------------------
# the criterion that is in PlugSlateBlock.kt today (transcribed line by line)
# ----------------------------------------------------------------------------

def face_axes(plug):
    """faceAxes(bs) = setOf(Y, FACING.axis)."""
    return {"Y", AXIS[plug["facing"]]}


def source_axes(src):
    """sourceAxes(state): PlugSlate -> faceAxes; BlockSlate -> WALL ? {FACING.axis} : {Y}."""
    if src["kind"] == "plug":
        return face_axes(src)
    if src["attach"] == "WALL":
        return {AXIS[src["facing"]]}
    return {"Y"}


def code_check(src, plug, d):
    ax = source_axes(src)
    carrying = [a for a in ax if a != AXIS[d]]
    if not carrying:
        return True, "carrying empty -> return true (no test at all)", True
    mine = face_axes(plug)
    ok = any(a in mine for a in carrying)
    return ok, f"carrying={sorted(carrying)} mine={sorted(mine)}", False


# ----------------------------------------------------------------------------
# proposed exact criterion (state only, no geometry).  Verified on all 288.
#   srcThin  = the source slab's thin axis
#   srcGlue  = the cell face the source slab is glued to
#   plugGlue = the cell face the plug plate is glued to
# ----------------------------------------------------------------------------

def slab_state(src):
    if src["attach"] == "FLOOR":
        return "Y", "DOWN"
    if src["attach"] == "CEILING":
        return "Y", "UP"
    # upstream wall: thin along FACING.axis, glued to FACING.opposite
    return AXIS[src["facing"]], OPP[src["facing"]]


def rule_check(src, plug, d):
    n = AXIS[d]
    thin, glue = slab_state(src)
    plate_glue = "DOWN" if plug["half"] == "BOTTOM" else "UP"
    f = plug["facing"]
    # 1) the wave crosses the source slab's thin axis: only the slab glued to the
    #    shared face can carry it, and its section is then the whole face, so the
    #    plug side never matters.
    if n == thin:
        return d == glue
    # 2) vertical entry, horizontal source slab (both slabs span Y fully):
    #    the plug must offer a piece on that face whose 1px band is on our side.
    if n == "Y":
        return (plate_glue == OPP[d]) or (AXIS[f] != thin) or (f == glue)
    # 3) horizontal entry, floor/ceiling slate: the y bands must meet
    #    (plate on the same side, or the wall crossing / glued to that face).
    if thin == "Y":
        return (plate_glue == glue) or (AXIS[f] != n) or (f == OPP[d])
    # 4) horizontal entry, wall slate crossing a perpendicular horizontal axis:
    #    the slab spans that axis fully, the plug plate always meets it.
    return True


# ----------------------------------------------------------------------------
# enumeration
# ----------------------------------------------------------------------------
SLATES = [{"kind": "slate", "attach": "FLOOR", "facing": None},
          {"kind": "slate", "attach": "CEILING", "facing": None}]
for f in HORIZ:
    SLATES.append({"kind": "slate", "attach": "WALL", "facing": f})

PLUGS = [{"kind": "plug", "half": h, "facing": f}
         for h in ("BOTTOM", "TOP") for f in HORIZ]


def slate_name(s):
    return s["attach"] if s["attach"] != "WALL" else f"WALL({s['facing']})"


def plug_name(p):
    return f"{p['half']}/{p['facing']}"


def run():
    rows = []
    for s, p, d in itertools.product(SLATES, PLUGS, ALLDIRS):
        ph, wit, area, strict = phys_check(s, p, d)
        cd, why, deg = code_check(s, p, d)
        rows.append(dict(src=s, plug=p, d=d, phys=ph, code=cd, deg=deg,
                         strict=strict, area=area, wit=wit, why=why,
                         rule=rule_check(s, p, d)))
    return rows


def bucket(r):
    a = r["src"]["attach"]
    if a == "WALL":
        h = AXIS[r["src"]["facing"]]
        dn = AXIS[r["d"]]
        rel = "dir==slabAxis" if dn == h else ("dir==Y" if dn == "Y" else "dir==otherHoriz")
        return f"WALL({r['src']['facing']}) / {rel}"
    return f"{a} / dir.axis={AXIS[r['d']]}"


def report(rows):
    n = len(rows)
    n_phys = sum(r["phys"] for r in rows)
    n_code = sum(r["code"] for r in rows)
    miss = [r for r in rows if r["phys"] and not r["code"]]
    over = [r for r in rows if r["code"] and not r["phys"]]
    bad = [r for r in rows if r["rule"] != r["phys"]]
    print(f"rows={n}  phys_true={n_phys}  code_true={n_code}  "
          f"missed={len(miss)}  spurious={len(over)}  agree={n - len(miss) - len(over)}")
    print(f"rule vs phys mismatches = {len(bad)}")
    for r in bad[:20]:
        print("  RULEBAD", slate_name(r["src"]), plug_name(r["plug"]), r["d"], r["phys"], r["rule"])
    degen = [r for r in rows if r["phys"] and not r["strict"]]
    print(f"phys-true contacts that are only degenerate edge/corner = {len(degen)}")
    print("areas of phys-true contacts:", sorted({r["area"] for r in rows if r["phys"]}))
    for label, grp in (("missed", miss), ("spurious", over)):
        hist = {}
        for r in grp:
            hist[r["area"]] = hist.get(r["area"], 0) + 1
        print(f"  {label} contact areas: " +
              ", ".join(f"{k:g}px2 x{v}" for k, v in sorted(hist.items())))

    # the plug can never be the limiting side: plate + wall union covers all 6 faces
    covered = 0
    for p in PLUGS:
        for d in ALLDIRS:
            face, c = AXIDX[AXIS[d]], boundary_coord(d)
            if any(touches(bx, face, c) for _, bx in plug_pieces(p["half"], p["facing"])):
                covered += 1
    print(f"plug states that own a piece on face enterDir.opposite: {covered}/{len(PLUGS) * 6}")

    # the source slate, by contrast, only ever has ONE piece
    src_faces = {}
    for s in SLATES:
        cnt = 0
        for d in ALLDIRS:
            face, c = AXIDX[AXIS[d]], boundary_coord(d)
            if any(touches(shifted(bx, d), face, c)
                   for _, bx in slate_pieces(s["attach"], s["facing"])):
                cnt += 1
        src_faces[slate_name(s)] = cnt
    print("slate states that own a piece on face enterDir: " +
          ", ".join(f"{k}={v}/6" for k, v in src_faces.items()))
    assert not bad, "proposed rule disagrees with phys"
    assert covered == len(PLUGS) * 6

    def dump(title, group):
        print("\n== " + title + " ==")
        keys = sorted({bucket(r) for r in group})
        for k in keys:
            sub = [r for r in group if bucket(r) == k]
            print(f"  {k}: {len(sub)}")
            for d in ALLDIRS:
                per = [plug_name(r["plug"]) for r in sub if r["d"] == d]
                if per:
                    print(f"     dir={d:<5} {', '.join(per)}")

    dump("phys TRUE but code FALSE  (missed)", miss)
    dump("phys FALSE but code TRUE  (spurious)", over)

    print("\n== per source ==")
    print(f"  {'source':<12} {'agree':>5} {'missed':>6} {'spurious':>8} {'physT':>5} {'codeT':>5}")
    for s in SLATES:
        sub = [r for r in rows if r["src"] is s]
        m = sum(1 for r in sub if r["phys"] and not r["code"])
        o = sum(1 for r in sub if r["code"] and not r["phys"])
        print(f"  {slate_name(s):<12} {len(sub) - m - o:>5} {m:>6} {o:>8} "
              f"{sum(1 for r in sub if r['phys']):>5} {sum(1 for r in sub if r['code']):>5}")

    deg = [r for r in rows if r["deg"]]
    print(f"\n== code degeneracy: 'carrying empty -> true', {len(deg)}/{n} rows ==")
    for k in sorted({(slate_name(r['src']), r['d']) for r in deg}):
        sub = [r for r in deg if slate_name(r["src"]) == k[0] and r["d"] == k[1]]
        print(f"    {k[0]:<12} dir={k[1]:<5} rows={len(sub)}  "
              f"phys_true={sum(1 for r in sub if r['phys'])} phys_false={sum(1 for r in sub if not r['phys'])}")

    print("\n== phys matrix: #plug states (of 8) that connect, per (source, enterDir) ==")
    print(f"  {'source':<12} " + " ".join(f"{d:>6}" for d in ALLDIRS))
    for s in SLATES:
        cells = []
        for d in ALLDIRS:
            c = sum(1 for r in rows if r["src"] is s and r["d"] == d and r["phys"])
            k = sum(1 for r in rows if r["src"] is s and r["d"] == d and r["code"])
            cells.append(f"{c}/{k}")
        print(f"  {slate_name(s):<12} " + " ".join(f"{c:>6}" for c in cells))
    print("  (phys/code)")

    return rows, miss, over


# ----------------------------------------------------------------------------
# figure 1: all states in 3D
# ----------------------------------------------------------------------------

def draw_box3d(ax, box, color, alpha=0.45, lw=0.6):
    """MC (x,y,z) -> plot (X=x, Y=z, Z=y); MC-y is vertical."""
    (x0, x1), (y0, y1), (z0, z1) = box

    def P(x, y, z):
        return (x, z, y)

    quads = [
        [P(x0, y0, z0), P(x1, y0, z0), P(x1, y0, z1), P(x0, y0, z1)],
        [P(x0, y1, z0), P(x1, y1, z0), P(x1, y1, z1), P(x0, y1, z1)],
        [P(x0, y0, z0), P(x1, y0, z0), P(x1, y1, z0), P(x0, y1, z0)],
        [P(x0, y0, z1), P(x1, y0, z1), P(x1, y1, z1), P(x0, y1, z1)],
        [P(x0, y0, z0), P(x0, y0, z1), P(x0, y1, z1), P(x0, y1, z0)],
        [P(x1, y0, z0), P(x1, y0, z1), P(x1, y1, z1), P(x1, y1, z0)],
    ]
    ax.add_collection3d(Poly3DCollection(quads, facecolors=color, edgecolors="k",
                                         linewidths=lw, alpha=alpha))


def draw_cell3d(ax):
    e = 16
    for a, b in itertools.combinations(
            [(x, y, z) for x in (0, e) for y in (0, e) for z in (0, e)], 2):
        if sum(1 for i in range(3) if a[i] != b[i]) == 1:      # only cube edges
            ax.plot([a[0], b[0]], [a[2], b[2]], [a[1], b[1]],
                    color=C_CELL, lw=0.5, ls=(0, (3, 2)))


def setup3d(ax, title):
    ax.set_xlim(0, 16)
    ax.set_ylim(0, 16)
    ax.set_zlim(0, 16)
    ax.set_xticks([0, 16])
    ax.set_yticks([0, 16])
    ax.set_zticks([0, 16])
    ax.tick_params(labelsize=5, pad=-3)
    ax.set_box_aspect((1, 1, 1))
    ax.set_title(title, fontsize=8, pad=1)
    ax.view_init(elev=20, azim=-62)
    ax.grid(False)


def fig_states(path):
    fig = plt.figure(figsize=(13.5, 12.4))
    gs = fig.add_gridspec(4, 4, hspace=0.06, wspace=0.02,
                          left=0.02, right=0.98, top=0.845, bottom=0.02)

    order = SLATES + PLUGS
    for i, st in enumerate(order):
        r, c = divmod(i, 4)
        ax = fig.add_subplot(gs[r, c], projection="3d")
        if st["kind"] == "slate":
            for nm, bx in slate_pieces(st["attach"], st["facing"]):
                draw_box3d(ax, bx, C_SLATE, alpha=0.55)
            setup3d(ax, f"slate: {slate_name(st)}" +
                    ("   (normal UP)" if st["attach"] == "FLOOR" else
                     "   (normal DOWN)" if st["attach"] == "CEILING" else
                     "   (FACING = exposed normal)"))
        else:
            for nm, bx in plug_pieces(st["half"], st["facing"]):
                draw_box3d(ax, bx, C_PLATE if nm == "plate" else C_WALL, alpha=0.5)
            setup3d(ax, f"plug: {plug_name(st)}")
        draw_cell3d(ax)

    fig.suptitle("Upstream slate (BlockSlate, 6 states)   vs   this mod's double slab (PlugSlateBlock, 8 states)\n"
                 "1/16 units, cell 0..16, MC-y vertical;  plot axes: X = mc x, Y = mc z, Z = mc y",
                 fontsize=10.5, y=0.985)
    fig.text(0.5, 0.918, "slate = one 1px slab   |   plug = 1px plate + 1px wall (wall is 1px thinner on the plate side)",
             ha="center", fontsize=8.5, color="#444444")
    handles = [Patch(facecolor=C_SLATE, alpha=0.6, edgecolor="k", label="slate slab (1px)"),
               Patch(facecolor=C_PLATE, alpha=0.6, edgecolor="k", label="plug plate (1px)"),
               Patch(facecolor=C_WALL, alpha=0.6, edgecolor="k", label="plug wall (1px)"),
               Line2D([0], [0], color=C_CELL, lw=0.8, ls=(0, (3, 2)), label="cell edge 0..16")]
    fig.legend(handles=handles, loc="upper center", ncol=4, frameon=False, fontsize=8.5,
               bbox_to_anchor=(0.5, 0.955))
    fig.savefig(path, dpi=170)
    plt.close(fig)
    print("wrote", path)


# ----------------------------------------------------------------------------
# figure 2: decisive cases, elevation + boundary-plane sections
# ----------------------------------------------------------------------------

def rect_of(box, n):
    """2D rectangle of a piece on the shared plane, in (horizontal, vertical)
    display order.  horizontal = the remaining horizontal axis, vertical = MC y
    (or MC z when the shared plane is horizontal)."""
    (x0, x1), (y0, y1), (z0, z1) = box
    if n == 0:
        return (z0, z1, y0, y1)          # plane YZ -> (z, y)
    if n == 2:
        return (x0, x1, y0, y1)          # plane XY -> (x, y)
    return (x0, x1, z0, z1)              # plane XZ -> (x, z)   (enterDir vertical)


def plane_labels(n):
    if n == 0:
        return "z (1/16)", "y (1/16)"
    if n == 2:
        return "x (1/16)", "y (1/16)"
    return "x (1/16)", "z (1/16)"


def draw_case(fig, gs, row, col, rec):
    src, plug, d = rec["src"], rec["plug"], rec["d"]
    geom = case_geometry(src, plug, d)
    n, c = geom["n"], geom["c"]
    shift = DIRV[d]

    # ---------- panel A: elevation across the shared plane ----------
    ax = fig.add_subplot(gs[row, col])
    horiz_axis = n if n != 1 else 0          # vertical entry -> show x sideways

    def band(box):
        return box[horiz_axis][0], box[horiz_axis][1], box[1][0], box[1][1]

    if n == 1:
        hlim = (-0.6, 16.6)
        vlim = (-16.6, 0.6) if shift[1] > 0 else (16 - 0.6, 32.6)
        vlim = (-16.6, 16.6) if shift[1] > 0 else (15.4, 32.6)
    else:
        hlim = (-16.6, 16.6) if shift[horiz_axis] > 0 else (-0.6, 32.6)
        vlim = (-0.6, 16.6)
    ax.set_xlim(*hlim)
    ax.set_ylim(*vlim)
    ax.set_aspect("equal")
    ax.set_xticks([t for t in (0, 16, -16, 32) if hlim[0] <= t <= hlim[1]])
    ax.set_yticks([t for t in (0, 16, -16, 32) if vlim[0] <= t <= vlim[1]])
    ax.tick_params(labelsize=5, pad=1)

    def cellrect(h0, h1, v0, v1):
        ax.add_patch(Rectangle((h0, v0), h1 - h0, v1 - v0, fill=False,
                               ec=C_CELL, lw=0.6, ls=(0, (3, 2))))

    if n == 1:
        sc = -16 if shift[1] > 0 else 16
        cellrect(0, 16, 0, 16)
        cellrect(0, 16, sc, sc + 16)
        ax.text(8, (sc + sc + 16) / 2, "source\ncell", ha="center", va="center",
                fontsize=5, color="#666666")
        ax.text(8, 8, "plug\ncell", ha="center", va="center", fontsize=5, color="#666666")
    else:
        off_ = -16 if shift[horiz_axis] > 0 else 16
        cellrect(0, 16, 0, 16)
        cellrect(off_, off_ + 16, 0, 16)
        ax.text(off_ + 8, 8, "source\ncell", ha="center", va="center",
                fontsize=5, color="#666666")
        ax.text(8, 8, "plug\ncell", ha="center", va="center", fontsize=5, color="#666666")

    for nm, bx in geom["src"]:
        b = band(bx)
        ax.add_patch(Rectangle((b[0], b[2]), b[1] - b[0], b[3] - b[2],
                               fc=C_SLATE, ec="k", lw=0.6, alpha=0.55, zorder=3))
    for nm, bx in geom["plug"]:
        b = band(bx)
        ax.add_patch(Rectangle((b[0], b[2]), b[1] - b[0], b[3] - b[2],
                               fc=C_PLATE if nm.endswith("plate") else C_WALL,
                               ec="k", lw=0.6, alpha=0.55, zorder=3))

    # shared plane + the band each reaching piece occupies on it
    col_of = lambda nm: C_SLATE if nm.startswith("src") else (
        C_WALL if nm.endswith("wall") else C_PLATE)
    if n == 1:
        ax.axhline(c, color="k", lw=1.0, ls="--", zorder=2)
        for nm, bx in geom["src"] + geom["plug"]:
            if touches(bx, n, c):
                ax.plot([bx[0][0], bx[0][1]], [c, c], color=col_of(nm), lw=3.2,
                        solid_capstyle="butt", zorder=4)
    else:
        ax.axvline(c, color="k", lw=1.0, ls="--", zorder=2)
        for nm, bx in geom["src"] + geom["plug"]:
            if touches(bx, n, c):
                ax.plot([c, c], [bx[1][0], bx[1][1]], color=col_of(nm), lw=3.2,
                        solid_capstyle="butt", zorder=4)
    ax.set_title("elevation across the shared plane (thick bar = piece reaching it)",
                 fontsize=6.2, pad=2)

    # ---------- panel B: the sections on the shared plane ----------
    ax2 = fig.add_subplot(gs[row, col + 1])
    s_sec, p_sec = [], []
    for nm, bx in geom["src"]:
        if touches(bx, n, c):
            s_sec.append((nm, rect_of(bx, n)))
    for nm, bx in geom["plug"]:
        if touches(bx, n, c):
            p_sec.append((nm, rect_of(bx, n)))

    ax2.set_xlim(-0.6, 16.6)
    ax2.set_ylim(-0.6, 16.6)
    ax2.set_aspect("equal")
    ax2.set_xticks([0, 16])
    ax2.set_yticks([0, 16])
    ax2.tick_params(labelsize=5, pad=1)
    for nm, r in p_sec:
        ax2.add_patch(Rectangle((r[0], r[2]), r[1] - r[0], r[3] - r[2],
                                fc=col_of(nm), ec="k", lw=0.6, alpha=0.5, zorder=3))
    for nm, r in s_sec:
        ax2.add_patch(Rectangle((r[0], r[2]), r[1] - r[0], r[3] - r[2],
                                fc=C_SLATE, ec="k", lw=0.6, alpha=0.35, hatch="///",
                                zorder=4))
    hits = 0
    for snm, r in s_sec:
        for pnm, q in p_sec:
            ov = overlap2d(r, q)
            if ov is not None:
                hits += 1
                ax2.add_patch(Rectangle((ov[0], ov[2]), max(ov[1] - ov[0], 0.45),
                                        max(ov[3] - ov[2], 0.45),
                                        fc=C_HIT, ec="k", lw=0.9, alpha=0.5, zorder=6))
    la, lb = plane_labels(n)
    ax2.set_xlabel(la, fontsize=5.5, labelpad=0)
    ax2.set_ylabel(lb, fontsize=5.5, labelpad=0)
    if hits:
        ax2.set_title(f"2D sections on the plane: {hits} overlap (green)", fontsize=6.2, pad=2)
    else:
        ax2.set_title("2D sections on the plane: DISJOINT (no contact)", fontsize=6.2,
                      pad=2, color="#b00020")

    # ---------- card caption ----------
    if rec["phys"] == rec["code"]:
        tag, colour = "agree", "#2f7d1f"
    elif rec["phys"]:
        tag, colour = "MISSED  (phys=T code=F)", "#b00020"
    else:
        tag, colour = "SPURIOUS  (phys=F code=T)", "#b00020"
    ax.text(-0.02, 1.30,
            f"src={slate_name(src)}   plug={plug_name(plug)}   dir={d}\n"
            f"phys={'T' if rec['phys'] else 'F'}  code={'T' if rec['code'] else 'F'}"
            f"  ->  {tag}",
            transform=ax.transAxes, fontsize=6.8, color=colour, va="bottom")


def fig_cases(rows, path):
    wanted = [
        ("FLOOR", None, "BOTTOM", "WEST", "EAST"),
        ("FLOOR", None, "TOP", "EAST", "EAST"),
        ("FLOOR", None, "TOP", "WEST", "EAST"),
        ("FLOOR", None, "TOP", "EAST", "UP"),
        ("CEILING", None, "BOTTOM", "EAST", "EAST"),
        ("CEILING", None, "BOTTOM", "WEST", "EAST"),
        ("WALL", "EAST", "BOTTOM", "NORTH", "UP"),
        ("WALL", "EAST", "TOP", "EAST", "UP"),
        ("WALL", "EAST", "TOP", "WEST", "UP"),
        ("WALL", "EAST", "BOTTOM", "NORTH", "NORTH"),
        ("WALL", "EAST", "BOTTOM", "WEST", "EAST"),
        ("WALL", "EAST", "BOTTOM", "EAST", "WEST"),
    ]

    def pick(attach, sf, half, pf, d):
        for x in rows:
            if (x["src"]["attach"] == attach and x["src"]["facing"] == sf
                    and x["plug"]["half"] == half and x["plug"]["facing"] == pf
                    and x["d"] == d):
                return x
        raise KeyError((attach, sf, half, pf, d))

    recs = [pick(*w) for w in wanted]
    for r in recs:
        print(f"case src={slate_name(r['src']):<12} plug={plug_name(r['plug']):<14} "
              f"dir={r['d']:<5} phys={r['phys']!s:<5} code={r['code']!s:<5} {r['wit']}")

    fig = plt.figure(figsize=(16.0, 11.4))
    gs = fig.add_gridspec(4, 6, hspace=0.75, wspace=0.42,
                          left=0.04, right=0.975, top=0.885, bottom=0.04)
    for i, rec in enumerate(recs):
        row, colcard = divmod(i, 3)
        draw_case(fig, gs, row, colcard * 2, rec)
    fig.suptitle("Slate -> double slab: 12 decisive adjacency cases   "
                 "(1/16 units;  teal = upstream slate slab,  blue = plug plate,  orange = plug wall)",
                 fontsize=11, y=0.975)
    fig.text(0.5, 0.935,
             "left panel: both cells in elevation, dashed line = shared plane, thick bar = the piece that reaches it.   "
             "right panel: the same pieces as 2D sections on the shared plane; green = the contact patch.",
             ha="center", fontsize=8, color="#444444")
    fig.savefig(path, dpi=170)
    plt.close(fig)
    print("wrote", path)


# ----------------------------------------------------------------------------
if __name__ == "__main__":
    rows, miss, over = report(run())
    if "--nofig" not in sys.argv:
        fig_states(os.path.join(HERE, "slate_to_plug_states.png"))
        fig_cases(rows, os.path.join(HERE, "slate_to_plug_cases.png"))
