import unittest
import cloud_config

class CloudConfigTests(unittest.TestCase):
    def sample(self):return {'schemaVersion':1,'providers':{name:{'clientId':'','redirectUri':''} for name in ('aliyunpan','baidu')}}
    def test_empty_template_is_explicitly_unauthorized(self):
        result=cloud_config.validate(self.sample());self.assertFalse(any(x['registrationPresent'] or x['accountAuthorizationEnabled'] for x in result.values()))
    def test_public_registration_does_not_enable_account_auth(self):
        x=self.sample();x['providers']['aliyunpan']={'clientId':'public-client','redirectUri':'https://example.org/oauth/return'}
        result=cloud_config.validate(x)['aliyunpan'];self.assertTrue(result['registrationPresent']);self.assertFalse(result['accountAuthorizationEnabled']);self.assertNotIn('public-client',str(result))
    def test_secret_fields_are_rejected(self):
        x=self.sample();x['providers']['baidu']['clientSecret']='not-an-accepted-field'
        with self.assertRaises(ValueError):cloud_config.validate(x)
    def test_insecure_or_authorization_responses_are_rejected(self):
        for uri in ('http://example.org/return','https://example.org/return?code=sample','javascript:alert(1)'):
            x=self.sample();x['providers']['baidu']['redirectUri']=uri
            with self.assertRaises(ValueError):cloud_config.validate(x)
    def test_extra_providers_cannot_change_launch_targets(self):
        x=self.sample();x['providers']['other']={'clientId':'public','redirectUri':''}
        with self.assertRaises(ValueError):cloud_config.validate(x)

    def test_native_registration_can_leave_redirect_empty(self):
        x=self.sample();x['providers']['aliyunpan']['clientId']='public-client'
        result=cloud_config.validate(x)['aliyunpan']
        self.assertTrue(result['registrationPresent']);self.assertFalse(result['redirectRecorded'])
        self.assertFalse(result['accountAuthorizationEnabled'])

    def test_selected_provider_does_not_require_other_provider_registration(self):
        import json,subprocess,sys,tempfile
        from pathlib import Path
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'public.json';data=self.sample()
            data['providers']['aliyunpan']['clientId']='public-client'
            path.write_text(json.dumps(data))
            command=[sys.executable,str(Path(cloud_config.__file__)),'--config',str(path),'--require-registration']
            selected=subprocess.run(command+['--provider','aliyunpan'],capture_output=True,text=True)
            self.assertEqual(selected.returncode,0,selected.stderr)
            self.assertEqual(set(json.loads(selected.stdout)),{'aliyunpan'})
            self.assertNotIn('public-client',selected.stdout)
            self.assertNotEqual(subprocess.run(command,capture_output=True).returncode,0)
