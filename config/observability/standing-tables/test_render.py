import json
import unittest

from render import ROOT, render, validate


def bundle(env):
    bindings = {'__ENV__': env, '__LOKI_UID__': 'fixture-loki', '__PROMETHEUS_UID__': 'fixture-prom',
                '__SERVER_SELECTOR__': f'job="cards-server",deployment_environment="{env}"'}
    return [render(json.loads((ROOT / f'{name}.template.json').read_text()), bindings)
            for name in ('dashboard', 'alerts', 'queries')]


class RenderTest(unittest.TestCase):
    def test_environment_imports_have_disjoint_rule_namespaces(self):
        dev, prod = bundle('dev'), bundle('prod')
        for env, artifacts in (('dev', dev), ('prod', prod)):
            validate(artifacts, env)
            group = artifacts[1]['groups'][0]
            self.assertEqual(f'standing-tables-{env}', group['name'])
            self.assertTrue(all(rule['title'].endswith(f'({env})') for rule in group['rules']))
        dev_uids = {rule['uid'] for rule in dev[1]['groups'][0]['rules']}
        prod_uids = {rule['uid'] for rule in prod[1]['groups'][0]['rules']}
        self.assertEqual(5, len(dev_uids))
        self.assertEqual(5, len(prod_uids))
        self.assertTrue(dev_uids.isdisjoint(prod_uids))

    def test_wrong_environment_is_rejected(self):
        with self.assertRaises(ValueError):
            validate(bundle('prod'), 'dev')

    def test_duplicate_rule_uid_is_rejected(self):
        artifacts = bundle('dev')
        rules = artifacts[1]['groups'][0]['rules']
        rules[1]['uid'] = rules[0]['uid']
        with self.assertRaises(ValueError):
            validate(artifacts, 'dev')


if __name__ == '__main__':
    unittest.main()
