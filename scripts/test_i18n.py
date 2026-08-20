#!/usr/bin/env python3
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "res"


def keys(path: Path) -> set[str]:
    tree = ET.parse(path)
    return {node.attrib["name"] for node in tree.getroot().findall("string")}


class ResourceParityTests(unittest.TestCase):
    def test_locales_have_the_same_keys_as_english(self):
        english = keys(ROOT / "values" / "strings.xml")
        for locale in ("values-it", "values-es"):
            with self.subTest(locale=locale):
                self.assertEqual(english, keys(ROOT / locale / "strings.xml"))


if __name__ == "__main__":
    unittest.main()
