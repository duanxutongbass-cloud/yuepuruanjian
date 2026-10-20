from unittest.mock import patch
from security import digest
from test_accounts import AccountFixture


class ShortInvitationsTest(AccountFixture):
    def test_six_character_invitation_consumes_once_and_accepts_typed_case(self):
        code=self.call('/v1/admin/invitations',{})['invitation']
        self.assertEqual(6,len(code))
        self.assertTrue(any(c.isdigit() for c in code) and any(c.isalpha() for c in code))
        with self.service.connection() as db:
            self.assertIsNotNone(db.execute('SELECT 1 FROM invitations WHERE hash=?',(digest(code),)).fetchone())
        member=self.service.register(self.registration(invite=code.lower()),'127.0.0.1')
        self.assertEqual('joined',member['principal']['membership'])
        self.rejected(403,lambda:self.service.register(self.registration(invite=code,name='another'),'127.0.0.1'))

    def test_existing_long_invitation_remains_case_sensitive_and_usable(self):
        legacy='legacyCaseSensitiveInvitation12345'
        with self.service.connection() as db:
            db.execute('INSERT INTO invitations VALUES(?,?,?,0,?)',(digest(legacy),'member',int(self.now[0])+3600,self.admin_id))
        self.rejected(403,lambda:self.service.register(self.registration(invite=legacy.lower()),'127.0.0.1'))
        self.assertEqual('joined',self.service.register(self.registration(invite=legacy),'127.0.0.1')['principal']['membership'])

    def test_generation_retries_collision_and_never_replaces_existing_invitation(self):
        with patch('service.new_join_code',return_value='D4E5F6'):
            first=self.call('/v1/admin/invitations',{})['invitation']
        with patch('service.new_join_code',side_effect=[first,'A2B3C4']):
            self.assertEqual('A2B3C4',self.call('/v1/admin/invitations',{})['invitation'])
        with self.service.connection() as db:
            self.assertEqual(2,db.execute('SELECT count(*) FROM invitations').fetchone()[0])
        with patch('service.new_join_code',return_value=first):
            self.rejected(503,lambda:self.call('/v1/admin/invitations',{}))
        self.assertEqual('joined',self.service.register(self.registration(invite=first),'127.0.0.1')['principal']['membership'])

    def test_team_code_cannot_collide_with_one_person_invitation_or_bypass_review(self):
        with patch('service.new_join_code',return_value='A2B3C4'):
            invite=self.call('/v1/admin/invitations',{})['invitation']
        with patch('service.new_join_code',side_effect=[invite,'D4E5F6']):
            code=self.call('/v1/admin/join-code',dict(baseRevision=0))['code']
        self.assertEqual('D4E5F6',code)
        self.rejected(403,lambda:self.service.register(self.registration(invite=code),'127.0.0.1'))

    def test_expired_short_invitation_cannot_register(self):
        code=self.call('/v1/admin/invitations',{})['invitation']
        self.now[0]+=48*3600
        self.rejected(403,lambda:self.service.register(self.registration(invite=code),'127.0.0.1'))
