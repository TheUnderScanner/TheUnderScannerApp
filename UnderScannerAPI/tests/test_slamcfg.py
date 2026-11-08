"""Tests for slamcfg: validation, overrides, imposed keys, effective config, profile store.

    pip install pyyaml
    python -m unittest discover -s UnderScannerAPI/tests -v
"""
import sys
import tempfile
import unittest
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

import slamcfg as sc  # noqa: E402

FACTORY_FILE = HERE / "fixtures" / "fastlio2_mid360.yaml"
FACTORY = FACTORY_FILE.read_text(encoding="utf-8")
PCD = "/data/underscanner/.lastscan.pcd"


def algo(factory_source=FACTORY_FILE):
    return sc.make_fastlio2(factory_source=factory_source, pcd_out=Path(PCD),
                            lidar_topic="/livox/lidar", imu_topic="/livox/imu")


def params(text):
    return yaml.safe_load(text)["/**"]["ros__parameters"]


def line_of(text, needle):
    return next(i for i, l in enumerate(text.splitlines(), 1) if needle in l)


def errors(exc):
    return [i for i in exc.issues if i.level == "error"]


class FormatTest(unittest.TestCase):
    def test_floats_round_trip_through_pyyaml(self):
        for f in (0.3, 1.0, 0.0001, 1e-05, 1e20, -0.011, 123456789.125, 2.5e-12):
            s = sc.fmt_value(f)
            self.assertTrue(sc.same(yaml.safe_load(s), f), f"{f} -> {s}")

    def test_strings_and_lists(self):
        for v in ("/data/x.pcd", "Résidence", 'a"b', [1.0, -2.5, 0.0], [True, False], -1, None):
            self.assertTrue(sc.same(yaml.safe_load(sc.fmt_value(v)), v), v)


class ProfileCheckTest(unittest.TestCase):
    def setUp(self):
        self.a = algo()

    def test_factory_is_clean(self):
        text, warnings = sc.check_profile(self.a, FACTORY, FACTORY)
        self.assertEqual(text, FACTORY)
        self.assertEqual(warnings, [])

    def test_yaml_syntax_error_has_line(self):
        bad = FACTORY.replace("        max_iteration: 3", "        max_iteration: [3")
        with self.assertRaises(sc.ConfigError) as cm:
            sc.check_profile(self.a, FACTORY, bad)
        self.assertTrue(errors(cm.exception)[0].msg.startswith("YAML invalide"))
        self.assertIsNotNone(errors(cm.exception)[0].line)

    def test_empty(self):
        with self.assertRaises(sc.ConfigError):
            sc.check_profile(self.a, FACTORY, "   \n# rien\n")

    def test_unknown_key_with_line(self):
        bad = FACTORY.replace("        max_iteration: 3",
                              "        max_iteration: 3\n        max_iterations: 4")
        with self.assertRaises(sc.ConfigError) as cm:
            sc.check_profile(self.a, FACTORY, bad)
        e = errors(cm.exception)
        self.assertEqual(len(e), 1)
        self.assertIn("clé inconnue : max_iterations", e[0].msg)
        self.assertEqual(e[0].line, line_of(bad, "max_iterations"))

    def test_unknown_section_reported_once(self):
        bad = FACTORY.rstrip() + "\n        loop:\n            a: 1\n            b: 2\n"
        with self.assertRaises(sc.ConfigError) as cm:
            sc.check_profile(self.a, FACTORY, bad)
        self.assertEqual([i.msg for i in errors(cm.exception)], ["clé inconnue : loop"])

    def test_wrong_root(self):
        bad = FACTORY.replace("/**:", "laser_mapping:")
        with self.assertRaises(sc.ConfigError) as cm:
            sc.check_profile(self.a, FACTORY, bad)
        self.assertIn("clé inconnue : laser_mapping", errors(cm.exception)[0].msg)
        self.assertEqual(errors(cm.exception)[0].line, 1)

    def test_bool_type_error(self):
        bad = FACTORY.replace("extrinsic_est_en:  true", "extrinsic_est_en:  1")
        with self.assertRaises(sc.ConfigError) as cm:
            sc.check_profile(self.a, FACTORY, bad)
        e = errors(cm.exception)[0]
        self.assertIn("mapping.extrinsic_est_en", e.msg)
        self.assertIn("booléen attendu", e.msg)
        self.assertEqual(e.line, line_of(bad, "extrinsic_est_en"))

    def test_float_where_int_expected(self):
        bad = FACTORY.replace("point_filter_num: 3", "point_filter_num: 2.5")
        with self.assertRaises(sc.ConfigError) as cm:
            sc.check_profile(self.a, FACTORY, bad)
        self.assertIn("entier attendu", errors(cm.exception)[0].msg)

    def test_section_replaced_by_scalar(self):
        bad = FACTORY.replace("        publish:\n", "        publish: 3\n        publish_x:\n")
        with self.assertRaises(sc.ConfigError):
            sc.check_profile(self.a, FACTORY, bad)

    def test_list_length(self):
        bad = FACTORY.replace("extrinsic_T: [ -0.011, -0.02329, 0.04412 ]", "extrinsic_T: [ 0.0, 0.0 ]")
        with self.assertRaises(sc.ConfigError) as cm:
            sc.check_profile(self.a, FACTORY, bad)
        self.assertIn("liste de 3 valeurs attendue, reçu 2", errors(cm.exception)[0].msg)

    def test_duplicate_key(self):
        bad = FACTORY.replace("        filter_size_map: 0.5",
                              "        filter_size_map: 0.5\n        filter_size_map: 0.3")
        with self.assertRaises(sc.ConfigError) as cm:
            sc.check_profile(self.a, FACTORY, bad)
        e = errors(cm.exception)[0]
        self.assertIn("clé en double : filter_size_map", e.msg)
        self.assertEqual(e.line, line_of(bad, "filter_size_map: 0.3"))

    def test_all_errors_reported(self):
        bad = (FACTORY.replace("point_filter_num: 3", "point_filter_num: abc")
                      .replace("blind: 0.5", "blind: true"))
        with self.assertRaises(sc.ConfigError) as cm:
            sc.check_profile(self.a, FACTORY, bad)
        self.assertEqual(len(errors(cm.exception)), 2)

    def test_int_to_float_is_normalized_in_text(self):
        prof = FACTORY.replace("filter_size_map: 0.5", "filter_size_map: 1")
        text, warnings = sc.check_profile(self.a, FACTORY, prof)
        self.assertIn("filter_size_map: 1.0", text)
        self.assertIsInstance(params(text)["filter_size_map"], float)
        self.assertIn("# ONLY turn on when external", text)          # comments untouched
        self.assertTrue(any("converti" in w.msg for w in warnings))

    def test_int_list_normalized(self):
        prof = FACTORY.replace("extrinsic_T: [ -0.011, -0.02329, 0.04412 ]", "extrinsic_T: [0, 0, 0]")
        text, _ = sc.check_profile(self.a, FACTORY, prof)
        self.assertTrue(sc.same(params(text)["mapping"]["extrinsic_T"], [0.0, 0.0, 0.0]))

    def test_forced_key_differs_is_warning(self):
        prof = FACTORY.replace(f'map_file_path: "{PCD}"', 'map_file_path: "./test.pcd"')
        text, warnings = sc.check_profile(self.a, FACTORY, prof)
        self.assertEqual(text, prof)                                   # profile kept as written
        w = [x for x in warnings if "imposée" in x.msg]
        self.assertEqual(len(w), 1)
        self.assertEqual(w[0].line, line_of(prof, "map_file_path"))

    def test_missing_key_is_warning(self):
        prof = FACTORY.replace("        cube_side_length: 1000.0\n", "")
        _, warnings = sc.check_profile(self.a, FACTORY, prof)
        self.assertTrue(any("cube_side_length absente" in w.msg for w in warnings))


class OverridesTest(unittest.TestCase):
    def setUp(self):
        self.a = algo()
        self.fact = sc.load_factory(FACTORY)

    def parse(self, text):
        return sc.parse_overrides(self.a, self.fact, text)

    def test_leaf_name_dotted_and_bench_syntax(self):
        o = self.parse("filter_size_map: 0.3\nmapping.det_range: 60\n# comment\n\nblind=0.3  # inline\n")
        root = self.a.params_root
        self.assertEqual(o[root + ("filter_size_map",)], 0.3)
        self.assertTrue(sc.same(o[root + ("mapping", "det_range")], 60.0))   # int -> float
        self.assertEqual(o[root + ("preprocess", "blind")], 0.3)

    def test_unknown_key_line(self):
        with self.assertRaises(sc.ConfigError) as cm:
            self.parse("filter_size_map: 0.3\nfilter_size_mapp: 0.3")
        e = errors(cm.exception)[0]
        self.assertEqual((e.line, e.where), (2, "overrides"))
        self.assertIn("clé inconnue", e.msg)

    def test_forced_key_refused(self):
        with self.assertRaises(sc.ConfigError) as cm:
            self.parse("pcd_save_en: false")
        self.assertIn("imposée", errors(cm.exception)[0].msg)

    def test_type_error(self):
        with self.assertRaises(sc.ConfigError) as cm:
            self.parse("max_iteration: beaucoup")
        self.assertIn("entier attendu", errors(cm.exception)[0].msg)

    def test_duplicate(self):
        with self.assertRaises(sc.ConfigError) as cm:
            self.parse("blind: 0.3\npreprocess.blind: 0.2")
        self.assertIn("déjà modifiée ligne 1", errors(cm.exception)[0].msg)

    def test_bad_format_and_missing_value(self):
        with self.assertRaises(sc.ConfigError) as cm:
            self.parse("juste du texte\nblind:")
        self.assertEqual([e.line for e in errors(cm.exception)], [1, 2])

    def test_ambiguous_needs_suffix(self):
        fact_text = "/**:\n  ros__parameters:\n    a:\n      x: 1.0\n    b:\n      x: 2.0\n"
        a = sc.make_fastlio2(factory_source=FACTORY_FILE, pcd_out=Path(PCD),
                             lidar_topic="/l", imu_topic="/i")
        fact = sc.load_factory(fact_text)
        with self.assertRaises(sc.ConfigError) as cm:
            sc.parse_overrides(a, fact, "x: 3")
        self.assertIn("ambiguë (a.x, b.x)", errors(cm.exception)[0].msg)
        o = sc.parse_overrides(a, fact, "b.x: 3")
        self.assertEqual(o[a.params_root + ("b", "x")], 3.0)


class EffectiveTest(unittest.TestCase):
    def setUp(self):
        self.a = algo()

    def test_factory_unchanged_except_header(self):
        eff = sc.build_effective(self.a, FACTORY, FACTORY, "", header=["scan X"])
        self.assertTrue(eff.preserved)
        self.assertEqual(eff.text, "# scan X\n" + FACTORY)
        self.assertEqual(eff.overrides, {})
        self.assertEqual(len(eff.sha256), 64)

    def test_voxel_profile_preserves_comments(self):
        prof = (FACTORY.replace("filter_size_surf: 0.5", "filter_size_surf: 0.3")
                       .replace("filter_size_map: 0.5", "filter_size_map: 0.3"))
        eff = sc.build_effective(self.a, FACTORY, prof, "mapping.det_range: 60\nblind: 0.3")
        p = params(eff.text)
        self.assertEqual((p["filter_size_surf"], p["filter_size_map"]), (0.3, 0.3))
        self.assertEqual(p["mapping"]["det_range"], 60.0)
        self.assertEqual(p["preprocess"]["blind"], 0.3)
        self.assertEqual(eff.overrides, {"mapping.det_range": 60.0, "preprocess.blind": 0.3})
        self.assertTrue(eff.preserved)
        self.assertIn("# -1 : all frames will be saved in ONE pcd file", eff.text)
        # only the changed values differ, line for line
        changed = [(a, b) for a, b in zip(prof.splitlines(), eff.text.splitlines()) if a != b]
        self.assertEqual(len(changed), 2)
        self.assertEqual(len(prof.splitlines()), len(eff.text.splitlines()))

    def test_forced_keys_rewritten(self):
        prof = (FACTORY.replace(f'map_file_path: "{PCD}"', 'map_file_path: "./test.pcd"')
                       .replace("pcd_save_en: true", "pcd_save_en: false")
                       .replace("interval: -1 ", "interval: 5 ")
                       .replace("scan_publish_en:  true", "scan_publish_en:  false"))
        eff = sc.build_effective(self.a, FACTORY, prof)
        p = params(eff.text)
        self.assertEqual(p["map_file_path"], PCD)
        self.assertIs(p["pcd_save"]["pcd_save_en"], True)
        self.assertEqual(p["pcd_save"]["interval"], -1)
        self.assertIs(p["publish"]["scan_publish_en"], True)
        self.assertTrue(eff.preserved)
        self.assertEqual(len([w for w in eff.warnings if "imposée" in w.msg]), 4)

    def test_block_sequence_override(self):
        prof = FACTORY.replace(
            "extrinsic_T: [ -0.011, -0.02329, 0.04412 ]",
            "extrinsic_T:\n                - -0.011\n                - -0.02329\n                - 0.04412")
        eff = sc.build_effective(self.a, FACTORY, prof, "extrinsic_T: [0.1, 0.2, 0.3]")
        p = params(eff.text)
        self.assertEqual(p["mapping"]["extrinsic_T"], [0.1, 0.2, 0.3])
        self.assertEqual(p["mapping"]["extrinsic_R"][0], 1.0)
        self.assertIn("publish:", eff.text)

    def test_multiline_flow_list_override(self):
        eff = sc.build_effective(self.a, FACTORY, FACTORY,
                                 "extrinsic_R: [0, 1, 0, 1, 0, 0, 0, 0, 1]")
        p = params(eff.text)
        self.assertTrue(sc.same(p["mapping"]["extrinsic_R"],
                                [0.0, 1.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0]))
        self.assertTrue(eff.preserved)

    def test_missing_forced_section_falls_back_to_dump(self):
        prof = FACTORY.split("        pcd_save:")[0]
        eff = sc.build_effective(self.a, FACTORY, prof, "filter_size_map: 0.3")
        self.assertFalse(eff.preserved)
        p = params(eff.text)
        self.assertEqual(p["pcd_save"], {"pcd_save_en": True, "interval": -1})
        self.assertEqual(p["filter_size_map"], 0.3)
        self.assertTrue(any("non conservée" in w.msg for w in eff.warnings))

    def test_profile_and_override_errors_together(self):
        prof = FACTORY.replace("point_filter_num: 3", "point_filter_num: x")
        with self.assertRaises(sc.ConfigError) as cm:
            sc.build_effective(self.a, FACTORY, prof, "nope: 1")
        self.assertEqual(sorted(e.where for e in errors(cm.exception)), ["overrides", "yaml"])

    def test_sha_is_of_written_text(self):
        import hashlib
        eff = sc.build_effective(self.a, FACTORY, FACTORY, "blind: 0.3", header=["h"])
        self.assertEqual(eff.sha256, hashlib.sha256(eff.text.encode()).hexdigest())

    def test_launch_cmd_uses_the_file(self):
        cmd = self.a.launch_cmd(Path("/data/underscanner/configs/2026-11-08_x_01.yaml"))
        self.assertIn("config_path:=/data/underscanner/configs", [c.replace("\\", "/") for c in cmd])
        self.assertIn("config_file:=2026-11-08_x_01.yaml", cmd)
        self.assertIn("rviz:=false", cmd)


class StoreTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name) / "slam"
        self.store = sc.ProfileStore(self.root, [algo()], log=lambda m: None)
        self.store.ensure_factories()

    def tearDown(self):
        for f in self.root.rglob("*.yaml"):
            f.chmod(0o644)          # Windows can't delete read-only files
        self.tmp.cleanup()

    def test_factory_snapshot_and_defaults(self):
        self.assertEqual(self.store.read("fastlio2", "usine"), FACTORY)
        self.assertEqual(self.store.defaults(), {"algo": "fastlio2", "profiles": {"fastlio2": "usine"}})
        lst = self.store.list("fastlio2")
        self.assertEqual([(p["name"], p["readonly"], p["is_default"]) for p in lst],
                         [("usine", True, True)])

    def test_factory_never_rewritten(self):
        src = Path(self.tmp.name) / "other.yaml"
        src.write_text(FACTORY.replace("blind: 0.5", "blind: 0.9"), encoding="utf-8")
        store = sc.ProfileStore(self.root, [algo(src)], log=lambda m: None)
        store.ensure_factories()
        self.assertEqual(store.factory_text("fastlio2"), FACTORY)

    def test_usine_is_readonly(self):
        with self.assertRaises(sc.Forbidden):
            self.store.save("fastlio2", "usine", FACTORY)
        with self.assertRaises(sc.Forbidden):
            self.store.delete("fastlio2", "usine")
        with self.assertRaises(sc.Forbidden):
            self.store.rename("fastlio2", "usine", "x")

    def test_create_voxel_profile_as_default(self):
        self.store.copy("fastlio2", "usine", "voxel-0.3")
        text = self.store.read("fastlio2", "voxel-0.3")
        text = (text.replace("filter_size_surf: 0.5", "filter_size_surf: 0.3")
                    .replace("filter_size_map: 0.5", "filter_size_map: 0.3"))
        self.store.save("fastlio2", "voxel-0.3", text)
        self.store.set_defaults(profiles={"fastlio2": "voxel-0.3"})
        a, name, ptext = self.store.resolve()
        self.assertEqual((a.id, name), ("fastlio2", "voxel-0.3"))
        self.assertEqual(params(ptext)["filter_size_map"], 0.3)
        with self.assertRaises(sc.Conflict):
            self.store.delete("fastlio2", "voxel-0.3")
        self.store.rename("fastlio2", "voxel-0.3", "voxel-03")
        self.assertEqual(self.store.defaults()["profiles"]["fastlio2"], "voxel-03")

    def test_save_validates_and_normalizes(self):
        with self.assertRaises(sc.ConfigError):
            self.store.save("fastlio2", "bad", FACTORY.replace("blind: 0.5", "blind: oui"))
        self.assertFalse((self.root / "fastlio2" / "bad.yaml").exists())
        self.store.save("fastlio2", "p", FACTORY.replace("blind: 0.5", "blind: 1"))
        self.assertIn("blind: 1.0", self.store.read("fastlio2", "p"))

    def test_names_and_conflicts(self):
        for bad in ("../x", ".x", "..", "", "a/b", "a\\b", "x" * 65):
            with self.assertRaises(sc.BadRequest):
                self.store.read("fastlio2", bad)
        self.store.copy("fastlio2", "usine", "a")
        with self.assertRaises(sc.Conflict):
            self.store.copy("fastlio2", "usine", "a")
        with self.assertRaises(sc.NotFound):
            self.store.read("fastlio2", "zz")
        with self.assertRaises(sc.NotFound):
            self.store.algo("glim")

    def test_vanished_default_falls_back(self):
        self.store.copy("fastlio2", "usine", "tmp")
        self.store.set_defaults(profiles={"fastlio2": "tmp"})
        (self.root / "fastlio2" / "tmp.yaml").unlink()
        self.assertEqual(self.store.defaults()["profiles"]["fastlio2"], "usine")


if __name__ == "__main__":
    unittest.main()
