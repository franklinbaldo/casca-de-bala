import json
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


class MetadataMergeTest(unittest.TestCase):
    def test_bc_pins_and_reviewed_ci_metadata_are_preserved(self):
        root = Path(__file__).resolve().parent.parent
        xml = ET.parse(root / 'android-app/gradle/verification-metadata.xml').getroot()
        ns = {'v': xml.tag.split('}')[0][1:]}
        actual = {}
        for c in xml.findall('v:components/v:component', ns):
            for a in c.findall('v:artifact', ns):
                key = (c.attrib['group'], c.attrib['name'], c.attrib['version'], a.attrib['name'])
                self.assertNotIn(key, actual, 'Duplicate metadata component/artifact')
                actual[key] = {s.attrib['value'] for s in a.findall('v:sha256', ns)}
        bc = json.loads((root / 'third_party/bcprov-1.86-download.json').read_text())
        for artifact, pin in bc.items():
            self.assertEqual(actual[('org.bouncycastle', 'bcprov-jdk18on', '1.86', artifact)], {pin['sha256']})
        reviewed = json.loads((root / 'ci/dependency-review.json').read_text())
        for pin in reviewed['entries']:
            group, name, version = pin['coordinate'].split(':')
            self.assertEqual(actual[(group, name, version, pin['artifact'])], {pin['sha256']})


if __name__ == '__main__':
    unittest.main()
