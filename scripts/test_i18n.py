#!/usr/bin/env python3
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "res"
LOCALE_CONFIG = ROOT / "xml" / "locales_config.xml"


def keys(path: Path) -> set[str]:
    tree = ET.parse(path)
    return {node.attrib["name"] for node in tree.getroot().findall("string")}


class ResourceParityTests(unittest.TestCase):
    LOCALES = (
        "values-it", "values-es", "values-fr", "values-de", "values-pt", "values-nl",
        "values-pl", "values-tr", "values-ru", "values-uk", "values-zh-rCN",
        "values-ja", "values-ko", "values-ar", "values-hi", "values-in",
    )
    LOCALE_TAGS = {
        "en", "it", "es", "fr", "de", "pt", "nl", "pl", "tr", "ru", "uk",
        "zh-CN", "ja", "ko", "ar", "hi", "id",
    }

    def test_locales_have_the_same_keys_as_english(self):
        english = keys(ROOT / "values" / "strings.xml")
        for locale in self.LOCALES:
            with self.subTest(locale=locale):
                self.assertEqual(english, keys(ROOT / locale / "strings.xml"))

    def test_android_locale_config_lists_every_supported_language(self):
        tree = ET.parse(LOCALE_CONFIG)
        configured = {node.attrib["{http://schemas.android.com/apk/res/android}name"]
                      for node in tree.getroot().findall("locale")}
        self.assertEqual(self.LOCALE_TAGS, configured)


if __name__ == "__main__":
    unittest.main()
