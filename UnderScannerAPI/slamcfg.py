"""SLAM algorithm registry + config profiles for the Underscanner backend.

Pure Python + PyYAML (no ROS, no FastAPI), so it is unit-tested on a PC:
    python -m unittest discover -s UnderScannerAPI/tests

------------------------------------------------------------------------------
REGISTRY
------------------------------------------------------------------------------
One SlamAlgo per algorithm answers everything scan_start / scan_stop need to know:
how to launch it on a given config file, which topics carry the preview cloud and
pose, how to save its map and where the .pcd lands, and which config keys the
backend imposes. Adding an algorithm = adding a make_<algo>() entry; scan_start /
scan_stop only talk to the SlamAlgo interface.

------------------------------------------------------------------------------
PROFILES  (DATA_ROOT/slam/)
------------------------------------------------------------------------------
    slam/defaults.json              {"algo": "fastlio2", "profiles": {"fastlio2": "voxel-0.3"}}
    slam/<algo>/usine.yaml          the factory config, snapshotted once, never rewritten
    slam/<algo>/<profile>.yaml      user profiles: plain algo YAML, replayable by the bench

The factory config is the reference every profile is checked against:
  * the text must be valid YAML, with no duplicate key;
  * no key unknown to the factory (the factory defines what is tunable);
  * no type change. An int where the factory has a float is converted ("1" -> "1.0"):
    ROS 2 rejects an integer for a parameter declared double, and the node would die
    at startup. Lists keep their length (extrinsics).
  * keys the backend imposes (output path, topics...) are rewritten whatever the profile
    says (a warning, not an error).
Errors carry the 1-based line of the offending key/value.

------------------------------------------------------------------------------
EFFECTIVE CONFIG  (what a scan really runs)
------------------------------------------------------------------------------
profile + per-scan overrides + imposed keys. Built by patching the profile's TEXT at
each changed value's position (yaml.compose marks), so comments and layout survive and
configs/<scan>.yaml stays readable and replayable as-is. If a target key is missing
from the profile (nowhere to patch), it falls back to a plain yaml.safe_dump. Either
way the result is re-parsed and checked before use.

Overrides are "key: value" lines (bench --set semantics, "key=value" accepted too): the
key is a leaf name, or a dotted suffix ("mapping.det_range") when the name is ambiguous;
it must exist in the factory config and match exactly one leaf.
"""
from __future__ import annotations

import hashlib
import json
import math
import os
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

import yaml

FACTORY = "usine"                                   # read-only profile name
# First char is a letter/digit (so never "." or ".."), no "/" "\": path-safe by construction.
PROFILE_RE = re.compile(r"^\w[\w.\-]{0,63}$")


# ============================================================================
# errors
# ============================================================================
@dataclass
class Issue:
    level: str                  # "error" | "warning"
    msg: str
    line: int | None = None     # 1-based, in the text it refers to
    where: str = "yaml"         # "yaml" (profile text) | "overrides"

    def as_dict(self) -> dict:
        return {"level": self.level, "msg": self.msg, "line": self.line, "where": self.where}


class ConfigError(Exception):
    """Validation failed; .issues holds every error (and the warnings found on the way)."""
    def __init__(self, issues: list[Issue]):
        self.issues = issues
        first = next((i for i in issues if i.level == "error"), None)
        super().__init__(first.msg if first else "config invalide")


class NotFound(Exception): pass
class Conflict(Exception): pass
class Forbidden(Exception): pass
class BadRequest(Exception): pass


# ============================================================================
# registry
# ============================================================================
@dataclass(frozen=True)
class SlamAlgo:
    id: str
    label: str
    description: str
    factory_source: Path                     # config the algo ships with -> usine.yaml
    launch: Callable[[Path], list]           # config file -> argv
    forced: dict                             # "dotted.key" (under params_root) -> value
    cloud_topic: str                         # preview cloud (world frame)
    odom_topic: str                          # preview pose / trajectory
    save_cmd: tuple                          # argv run while the algo is alive, at stop
    pcd_out: Path                            # where save_cmd writes the map
    warmup_s: float = 2.0                    # settle time after launch, before the bag
    params_root: tuple = ("/**", "ros__parameters")
    live: str = "accumulate"                 # how the preview behaves (future: loop closure)

    def launch_cmd(self, config: Path) -> list[str]:
        return list(self.launch(Path(config)))

    def describe(self) -> dict:
        return {"id": self.id, "label": self.label, "description": self.description,
                "forced": {k: v for k, v in self.forced.items()}, "live": self.live}


def make_fastlio2(*, factory_source: Path, pcd_out: Path,
                  lidar_topic: str, imu_topic: str) -> SlamAlgo:
    """FAST-LIO2, Ericsii/FAST_LIO_ROS2. Its mapping.launch.py builds the parameter file as
    PathJoin(config_path, config_file). Topics /cloud_registered and /Odometry are hard-coded
    in laserMapping.cpp; map_save writes the whole map to map_file_path, and only when
    pcd_save_en is true and interval is -1 (a positive interval splits it elsewhere)."""
    def launch(cfg: Path) -> list[str]:
        return ["ros2", "launch", "fast_lio", "mapping.launch.py",
                f"config_path:={cfg.parent.as_posix()}", f"config_file:={cfg.name}", "rviz:=false"]
    return SlamAlgo(
        id="fastlio2", label="FAST-LIO2",
        description="Odométrie LiDAR-inertielle (Ericsii/FAST_LIO_ROS2), sans fermeture de boucle",
        factory_source=Path(factory_source), launch=launch,
        forced={
            "map_file_path": Path(pcd_out).as_posix(),
            "pcd_save.pcd_save_en": True,
            "pcd_save.interval": -1,
            "common.lid_topic": lidar_topic,
            "common.imu_topic": imu_topic,
            "publish.scan_publish_en": True,     # false would kill /cloud_registered = the preview
        },
        cloud_topic="/cloud_registered", odom_topic="/Odometry",
        save_cmd=("ros2", "service", "call", "/map_save", "std_srvs/srv/Trigger"),
        pcd_out=Path(pcd_out), warmup_s=2.0,
    )


# ============================================================================
# value formatting / typing
# ============================================================================
def _fmt_float(f: float) -> str:
    """A float literal that YAML 1.1 (PyYAML) and ROS both read back as the same float."""
    if f != f:
        return ".nan"
    if math.isinf(f):
        return ".inf" if f > 0 else "-.inf"
    s = repr(f)
    if "e" in s or "E" in s:
        mant, exp = s.lower().split("e")
        if "." not in mant:
            mant += ".0"            # PyYAML wants a dot: "1e-05" would be a string
        if exp[0] not in "+-":
            exp = "+" + exp
        return f"{mant}e{exp}"
    return s if "." in s else s + ".0"


def fmt_value(v: Any) -> str:
    """Inline YAML for a scalar or list (flow style)."""
    if v is None:
        return "null"
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, int):
        return str(v)
    if isinstance(v, float):
        return _fmt_float(v)
    if isinstance(v, str):
        return json.dumps(v, ensure_ascii=False)     # JSON strings are valid YAML double-quoted
    if isinstance(v, (list, tuple)):
        return "[" + ", ".join(fmt_value(x) for x in v) + "]"
    raise TypeError(f"valeur non représentable : {v!r}")


def _kind(v: Any) -> str:
    if isinstance(v, bool): return "booléen"
    if isinstance(v, int): return "entier"
    if isinstance(v, float): return "réel"
    if isinstance(v, str): return "texte"
    if isinstance(v, list): return "liste"
    if isinstance(v, dict): return "section"
    if v is None: return "vide"
    return type(v).__name__


def _short(v: Any) -> str:
    try:
        s = fmt_value(v)
    except TypeError:
        s = repr(v)
    return s if len(s) <= 40 else s[:37] + "..."


def same(a: Any, b: Any) -> bool:
    """Type-strict equality (True != 1, 1 != 1.0), recursive for lists."""
    if isinstance(a, list) and isinstance(b, list):
        return len(a) == len(b) and all(same(x, y) for x, y in zip(a, b))
    return type(a) is type(b) and a == b


class _Mismatch(Exception): pass


def coerce(v: Any, ref: Any) -> Any:
    """v converted to ref's type when that is lossless and meaningful, else _Mismatch."""
    if isinstance(ref, bool):
        if isinstance(v, bool): return v
        raise _Mismatch(f"booléen attendu (true/false), reçu {_kind(v)} {_short(v)}")
    if isinstance(ref, int):
        if isinstance(v, int) and not isinstance(v, bool): return v
        raise _Mismatch(f"entier attendu, reçu {_kind(v)} {_short(v)}")
    if isinstance(ref, float):
        if isinstance(v, (int, float)) and not isinstance(v, bool): return float(v)
        raise _Mismatch(f"réel attendu, reçu {_kind(v)} {_short(v)}")
    if isinstance(ref, str):
        if isinstance(v, str): return v
        raise _Mismatch(f"texte attendu, reçu {_kind(v)} {_short(v)}")
    if isinstance(ref, list):
        if not isinstance(v, list):
            raise _Mismatch(f"liste attendue, reçu {_kind(v)} {_short(v)}")
        if len(v) != len(ref):
            raise _Mismatch(f"liste de {len(ref)} valeurs attendue, reçu {len(v)}")
        return [coerce(x, r) for x, r in zip(v, ref)]
    if ref is None:
        return v
    if type(v) is type(ref):
        return v
    raise _Mismatch(f"{_kind(ref)} attendu, reçu {_kind(v)}")


# ============================================================================
# parsing against the factory
# ============================================================================
def _construct(node: yaml.Node) -> Any:
    loader = yaml.SafeLoader("")
    try:
        return loader.construct_object(node, deep=True)
    finally:
        loader.dispose()


def _compose(text: str, where: str = "yaml") -> yaml.Node:
    try:
        node = yaml.compose(text, Loader=yaml.SafeLoader)
    except yaml.MarkedYAMLError as e:
        mark = e.problem_mark or e.context_mark
        msg = e.problem or e.context or str(e)
        raise ConfigError([Issue("error", f"YAML invalide : {msg}",
                                 mark.line + 1 if mark else None, where)])
    except yaml.YAMLError as e:
        raise ConfigError([Issue("error", f"YAML invalide : {e}", None, where)])
    if node is None:
        raise ConfigError([Issue("error", "fichier vide", None, where)])
    return node


def load_factory(text: str) -> dict:
    data = yaml.safe_load(text)
    if not isinstance(data, dict):
        raise ValueError("config d'usine : section racine attendue")
    return data


@dataclass
class _Leaf:
    value: Any          # coerced to the factory type
    original: Any       # as written in the profile
    node: yaml.Node     # value node (text position)
    line: int           # 1-based line of the key


@dataclass
class _Doc:
    leaves: dict = field(default_factory=dict)     # path tuple -> _Leaf
    issues: list = field(default_factory=list)


def _disp(algo: SlamAlgo, path: tuple) -> str:
    r = algo.params_root
    return ".".join(path[len(r):]) if path[:len(r)] == r and len(path) > len(r) else ".".join(path)


def _factory_leaves(data: dict, path: tuple = ()) -> dict:
    out = {}
    for k, v in data.items():
        p = path + (k,)
        if isinstance(v, dict):
            out.update(_factory_leaves(v, p))
        else:
            out[p] = v
    return out


def _walk(algo: SlamAlgo, node: yaml.Node, fact: dict, path: tuple, doc: _Doc):
    if not isinstance(node, yaml.MappingNode):
        where = _disp(algo, path) if path else "racine"
        doc.issues.append(Issue("error", f"{where} : section attendue", node.start_mark.line + 1))
        return
    seen: dict[str, int] = {}
    for knode, vnode in node.value:
        kline = knode.start_mark.line + 1
        key = _construct(knode)
        if not isinstance(key, str):
            doc.issues.append(Issue("error", f"clé non textuelle : {_short(key)}", kline))
            continue
        p = path + (key,)
        if key in seen:
            doc.issues.append(Issue("error", f"clé en double : {_disp(algo, p)} "
                                             f"(déjà ligne {seen[key]})", kline))
            continue
        seen[key] = kline
        if key not in fact:
            doc.issues.append(Issue("error", f"clé inconnue : {_disp(algo, p)}", kline))
            continue
        ref = fact[key]
        if isinstance(ref, dict):
            _walk(algo, vnode, ref, p, doc)
            continue
        if isinstance(vnode, yaml.MappingNode):
            doc.issues.append(Issue("error", f"{_disp(algo, p)} : {_kind(ref)} attendu, "
                                             f"pas une section", kline))
            continue
        v = _construct(vnode)
        try:
            cv = coerce(v, ref)
        except _Mismatch as e:
            doc.issues.append(Issue("error", f"{_disp(algo, p)} : {e}", vnode.start_mark.line + 1))
            continue
        doc.leaves[p] = _Leaf(cv, v, vnode, kline)
        if not same(cv, v):
            doc.issues.append(Issue("warning", f"{_disp(algo, p)} : {_short(v)} converti en "
                                               f"{_short(cv)} ({_kind(ref)})", vnode.start_mark.line + 1))


def _parse_profile(algo: SlamAlgo, fact: dict, text: str) -> _Doc:
    root = _compose(text)
    doc = _Doc()
    _walk(algo, root, fact, (), doc)
    errors = [i for i in doc.issues if i.level == "error"]
    if errors:
        raise ConfigError(doc.issues)
    forced = {algo.params_root + tuple(k.split(".")): v for k, v in algo.forced.items()}
    for p, ref in _factory_leaves(fact).items():
        if p not in doc.leaves and p not in forced:
            doc.issues.append(Issue("warning", f"{_disp(algo, p)} absente : l'algo prendra "
                                               f"sa valeur interne par défaut"))
    for p, v in forced.items():
        leaf = doc.leaves.get(p)
        if leaf is None:
            doc.issues.append(Issue("warning", f"{_disp(algo, p)} absente : ajoutée par le "
                                               f"backend ({_short(v)})"))
        elif not same(leaf.value, v):
            doc.issues.append(Issue("warning", f"{_disp(algo, p)} est imposée par le backend : "
                                               f"{_short(v)} remplacera {_short(leaf.original)}",
                                    leaf.line))
    return doc


# ============================================================================
# overrides
# ============================================================================
_OVR_RE = re.compile(r"^([^:=#\s][^:=]*?)\s*[:=]\s*(.*)$")


def parse_overrides(algo: SlamAlgo, fact: dict, text: str | None) -> dict:
    """Overrides text -> {path tuple: coerced value}. Raises ConfigError (where="overrides")."""
    out: dict = {}
    issues: list[Issue] = []
    if not text:
        return out
    leaves = _factory_leaves(fact)
    root = algo.params_root
    rel = {".".join(p[len(root):]): p for p in leaves if p[:len(root)] == root}
    forced = set(algo.forced)
    lines_of: dict = {}
    for n, raw in enumerate(text.splitlines(), 1):
        s = raw.strip()
        if not s or s.startswith("#"):
            continue
        m = _OVR_RE.match(s)
        if not m:
            issues.append(Issue("error", "format attendu : « clé: valeur »", n, "overrides"))
            continue
        key, rawv = m.group(1).strip(), m.group(2).strip()
        if rawv == "":
            issues.append(Issue("error", f"{key} : valeur manquante", n, "overrides"))
            continue
        try:
            val = yaml.safe_load(rawv)
        except yaml.YAMLError:
            issues.append(Issue("error", f"{key} : valeur illisible « {rawv} »", n, "overrides"))
            continue
        cands = [d for d in rel if d == key or d.endswith("." + key)]
        if not cands:
            issues.append(Issue("error", f"clé inconnue : {key}", n, "overrides"))
            continue
        if len(cands) > 1:
            issues.append(Issue("error", f"{key} est ambiguë ({', '.join(sorted(cands))}) : "
                                         f"précise-la", n, "overrides"))
            continue
        dotted = cands[0]
        if dotted in forced:
            issues.append(Issue("error", f"{dotted} est imposée par le backend", n, "overrides"))
            continue
        p = rel[dotted]
        if p in lines_of:
            issues.append(Issue("error", f"{dotted} déjà modifiée ligne {lines_of[p]}", n,
                                "overrides"))
            continue
        try:
            out[p] = coerce(val, leaves[p])
        except _Mismatch as e:
            issues.append(Issue("error", f"{dotted} : {e}", n, "overrides"))
            continue
        lines_of[p] = n
    if issues:
        raise ConfigError(issues)
    return out


# ============================================================================
# text patching
# ============================================================================
def _node_span(node: yaml.Node) -> tuple[int, int]:
    start, end = node.start_mark.index, node.end_mark.index
    # A block sequence's end mark sits on the next token (can swallow the newline and
    # comments after it): stop at the last item instead.
    if isinstance(node, yaml.SequenceNode) and not node.flow_style and node.value:
        end = _node_span(node.value[-1])[1]
    return start, end


def _patch(text: str, patches: list) -> str:
    for node, value in sorted(patches, key=lambda pv: pv[0].start_mark.index, reverse=True):
        a, b = _node_span(node)
        text = text[:a] + fmt_value(value) + text[b:]
    return text


def _get(data: Any, path: tuple):
    for k in path:
        if not isinstance(data, dict) or k not in data:
            raise KeyError(path)
        data = data[k]
    return data


def _set(data: dict, path: tuple, value: Any):
    for k in path[:-1]:
        nxt = data.get(k)
        if not isinstance(nxt, dict):
            nxt = data[k] = {}
        data = nxt
    data[path[-1]] = value


def _verify(text: str, expected: dict) -> bool:
    try:
        data = yaml.safe_load(text)
        return all(same(_get(data, p), v) for p, v in expected.items())
    except Exception:
        return False


# ============================================================================
# public API
# ============================================================================
def check_profile(algo: SlamAlgo, factory_text: str, text: str) -> tuple[str, list[Issue]]:
    """Validate a profile. Returns (normalized text, warnings); raises ConfigError.
    Normalized = the same text with type conversions written in ("1" -> "1.0"), so the
    stored profile is directly runnable by the algo and the bench."""
    fact = load_factory(factory_text)
    doc = _parse_profile(algo, fact, text)
    patches = [(lf.node, lf.value) for lf in doc.leaves.values() if not same(lf.value, lf.original)]
    out = _patch(text, patches) if patches else text
    if patches and not _verify(out, {p: lf.value for p, lf in doc.leaves.items()}):
        out = text      # couldn't rewrite in place: keep it, conversion still happens at scan start
    return out, [i for i in doc.issues if i.level == "warning"]


@dataclass
class Effective:
    text: str                   # exactly what is written to configs/<scan>.yaml
    sha256: str
    overrides: dict             # {"dotted.key": value} actually applied
    warnings: list              # [Issue]
    preserved: bool             # True = profile text patched in place (comments kept)


def build_effective(algo: SlamAlgo, factory_text: str, profile_text: str,
                    overrides_text: str | None = None,
                    header: list[str] | Callable[[dict], list[str]] | None = None) -> Effective:
    """profile + overrides + imposed keys -> the config a scan runs. Raises ConfigError
    with every error from the profile and the overrides."""
    fact = load_factory(factory_text)
    issues: list[Issue] = []
    doc = ovr = None
    try:
        doc = _parse_profile(algo, fact, profile_text)
    except ConfigError as e:
        issues += e.issues
    try:
        ovr = parse_overrides(algo, fact, overrides_text)
    except ConfigError as e:
        issues += e.issues
    if doc is None or ovr is None:
        raise ConfigError(issues)

    final = {p: lf.value for p, lf in doc.leaves.items()}
    final.update(ovr)
    for k, v in algo.forced.items():
        final[algo.params_root + tuple(k.split("."))] = v

    warnings = [i for i in doc.issues if i.level == "warning"]
    text = None
    preserved = False
    if all(p in doc.leaves for p in final):
        patches = [(doc.leaves[p].node, v) for p, v in final.items()
                   if not same(doc.leaves[p].original, v)]
        cand = _patch(profile_text, patches)
        if _verify(cand, final):
            text, preserved = cand, True
    if text is None:
        data = yaml.safe_load(profile_text)
        for p, v in final.items():
            _set(data, p, v)
        text = yaml.safe_dump(data, sort_keys=False, allow_unicode=True, default_flow_style=None)
        if not _verify(text, final):          # should never happen; refuse rather than guess
            raise ConfigError([Issue("error", "config effective incohérente (bug backend)")])
        warnings.append(Issue("warning", "mise en forme du profil non conservée (clé absente "
                                         "à compléter) : commentaires perdus dans la config du scan"))
    if not text.endswith("\n"):
        text += "\n"
    root = algo.params_root
    applied = {".".join(p[len(root):]): v for p, v in ovr.items()}
    if callable(header):
        header = header(applied)        # lets the caller describe the applied overrides
    if header:
        text = "".join(f"# {h}\n" for h in header) + text
    return Effective(text, hashlib.sha256(text.encode("utf-8")).hexdigest(), applied,
                     warnings, preserved)


# ============================================================================
# profile store
# ============================================================================
def _atomic_write(path: Path, text: str):
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(text, encoding="utf-8")
    os.replace(tmp, path)


class ProfileStore:
    """Profiles on disk, under DATA_ROOT/slam. Source of truth for the phone."""

    def __init__(self, root: Path, algos: list[SlamAlgo], log: Callable[[str], None] = print):
        self.root = Path(root)
        self.algos = {a.id: a for a in algos}
        self.log = log

    # ---- lookup ----
    def algo(self, algo_id: str) -> SlamAlgo:
        a = self.algos.get(algo_id)
        if a is None:
            raise NotFound(f"algo inconnu : {algo_id}")
        return a

    def _dir(self, algo_id: str) -> Path:
        return self.root / self.algo(algo_id).id

    def _path(self, algo_id: str, name: str) -> Path:
        if not isinstance(name, str) or not PROFILE_RE.match(name):
            raise BadRequest("nom de profil invalide (lettres, chiffres, . - _, 64 max, commence par une lettre ou un chiffre)")
        return self._dir(algo_id) / f"{name}.yaml"

    # ---- factory ----
    def ensure_factories(self):
        """Snapshot each algo's shipped config as usine.yaml, once. Never rewritten after:
        it is the reference profiles are validated against."""
        for a in self.algos.values():
            d = self.root / a.id
            d.mkdir(parents=True, exist_ok=True)
            f = d / f"{FACTORY}.yaml"
            if f.exists():
                continue
            try:
                text = a.factory_source.read_text(encoding="utf-8")
                load_factory(text)
            except Exception as e:
                self.log(f"[slam] {a.id}: config d'usine illisible ({a.factory_source}): {e}")
                continue
            _atomic_write(f, text)
            try:
                f.chmod(0o444)
            except OSError:
                pass
            self.log(f"[slam] {a.id}: usine.yaml créé depuis {a.factory_source}")

    def factory_text(self, algo_id: str) -> str:
        f = self._dir(algo_id) / f"{FACTORY}.yaml"
        if not f.exists():
            raise NotFound(f"config d'usine absente pour {algo_id}")
        return f.read_text(encoding="utf-8")

    # ---- defaults ----
    def _read_defaults(self) -> dict:
        try:
            d = json.loads((self.root / "defaults.json").read_text(encoding="utf-8"))
            return d if isinstance(d, dict) else {}
        except Exception:
            return {}

    def defaults(self) -> dict:
        """{"algo": id, "profiles": {algo: name}}, resolved: a missing or vanished entry
        falls back to the first algo / the factory profile."""
        raw = self._read_defaults()
        algo = raw.get("algo") if raw.get("algo") in self.algos else next(iter(self.algos))
        stored = raw.get("profiles") if isinstance(raw.get("profiles"), dict) else {}
        profiles = {}
        for a in self.algos:
            name = stored.get(a)
            ok = isinstance(name, str) and PROFILE_RE.match(name) and \
                (self.root / a / f"{name}.yaml").exists()
            profiles[a] = name if ok else FACTORY
        return {"algo": algo, "profiles": profiles}

    def set_defaults(self, algo: str | None = None, profiles: dict | None = None) -> dict:
        cur = self.defaults()
        if algo is not None:
            self.algo(algo)
            cur["algo"] = algo
        for a, name in (profiles or {}).items():
            if not self._path(a, name).exists():
                raise NotFound(f"profil introuvable : {a}/{name}")
            cur["profiles"][a] = name
        self.root.mkdir(parents=True, exist_ok=True)
        _atomic_write(self.root / "defaults.json", json.dumps(cur, indent=2, ensure_ascii=False))
        return cur

    # ---- profiles ----
    def list(self, algo_id: str) -> list[dict]:
        d = self._dir(algo_id)
        default = self.defaults()["profiles"].get(algo_id)
        out = []
        for p in sorted(d.glob("*.yaml")) if d.is_dir() else []:
            if not PROFILE_RE.match(p.stem):
                continue
            out.append({"name": p.stem, "readonly": p.stem == FACTORY,
                        "is_default": p.stem == default, "mtime": p.stat().st_mtime})
        out.sort(key=lambda x: (x["name"] != FACTORY, x["name"].lower()))
        return out

    def read(self, algo_id: str, name: str) -> str:
        f = self._path(algo_id, name)
        if not f.exists():
            raise NotFound(f"profil introuvable : {algo_id}/{name}")
        return f.read_text(encoding="utf-8")

    def validate(self, algo_id: str, text: str) -> tuple[str, list[Issue]]:
        return check_profile(self.algo(algo_id), self.factory_text(algo_id), text)

    def save(self, algo_id: str, name: str, text: str) -> tuple[str, list[Issue]]:
        """Validate then write (normalized). Creates the profile if needed."""
        f = self._path(algo_id, name)
        if name == FACTORY:
            raise Forbidden("le profil usine est en lecture seule : duplique-le")
        normalized, warnings = self.validate(algo_id, text)
        _atomic_write(f, normalized)
        return normalized, warnings

    def delete(self, algo_id: str, name: str):
        f = self._path(algo_id, name)
        if name == FACTORY:
            raise Forbidden("le profil usine ne peut pas être supprimé")
        if not f.exists():
            raise NotFound(f"profil introuvable : {algo_id}/{name}")
        if self.defaults()["profiles"].get(algo_id) == name:
            raise Conflict("c'est le profil par défaut : choisis-en un autre d'abord")
        f.unlink()

    def copy(self, algo_id: str, name: str, to: str):
        text = self.read(algo_id, name)
        dst = self._path(algo_id, to)
        if to == FACTORY:
            raise Forbidden("nom réservé au profil usine")
        if dst.exists():
            raise Conflict(f"le profil {to} existe déjà")
        _atomic_write(dst, text)
        try:
            dst.chmod(0o644)        # a copy of usine must not inherit read-only
        except OSError:
            pass

    def rename(self, algo_id: str, name: str, to: str):
        src, dst = self._path(algo_id, name), self._path(algo_id, to)
        if FACTORY in (name, to):
            raise Forbidden("le profil usine ne peut pas être renommé")
        if not src.exists():
            raise NotFound(f"profil introuvable : {algo_id}/{name}")
        if dst.exists():
            raise Conflict(f"le profil {to} existe déjà")
        was_default = self.defaults()["profiles"].get(algo_id) == name
        os.replace(src, dst)
        if was_default:
            self.set_defaults(profiles={algo_id: to})

    def resolve(self, algo_id: str | None = None, profile: str | None = None) -> tuple[SlamAlgo, str, str]:
        """(algo, profile name, profile text) for a scan, defaults filled in."""
        d = self.defaults()
        a = self.algo(algo_id or d["algo"])
        name = profile or d["profiles"].get(a.id, FACTORY)
        return a, name, self.read(a.id, name)
