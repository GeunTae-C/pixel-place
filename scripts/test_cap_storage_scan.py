"""역사 fixture 보존, no-follow 계상과 거부 경계 검증. 실제 파일시스템 링크 사용."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import phase18_cap_budget as b
from test_cap_budget import seed


class IdentityComparison(unittest.TestCase):
    """링크 생성 권한과 무관한 read 계약. 실제 일반 파일과 명시한 stat 대역 사용."""
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.target=Path(self.temp.name)/'unrelated-target'
        self.target.write_bytes(b'preserved')
        self.info=b.plain_path(self.target,False)

    @staticmethod
    def changed(info,**changes):
        fields=('st_dev','st_ino','st_mode','st_size','st_mtime_ns','st_ctime_ns')
        return SimpleNamespace(**(dict(zip(fields,b.file_identity(info)))|changes))

    def test_actual_regular_target_read(self):
        opened=b.read_historical_target(self.target,self.info)
        self.assertEqual(b.opened_file_identity(self.info),b.opened_file_identity(opened))
        self.assertEqual(b.file_identity(self.info),b.file_identity(self.target.lstat()))

    def test_reported_windows_ctime_difference_is_accepted(self):
        # 제공 fixture-readonly-audit.json의 실제 관측값 재생. Windows 실행으로 집계하지 않음.
        path_info=SimpleNamespace(st_dev=4914199498151845532,st_ino=6473924464491102,
                                  st_mode=33206,st_size=9,st_mtime_ns=1790594018704941700,
                                  st_ctime_ns=1790594018704427400)
        handle_info=self.changed(path_info,st_ctime_ns=1790594018704941700)
        with patch.object(b.os,'fstat',return_value=handle_info),patch.object(b,'plain_path',return_value=path_info):
            result=b.read_historical_target(self.target,path_info)
        self.assertEqual(b.file_identity(handle_info),b.file_identity(result))

    def test_other_cross_api_metadata_changes_are_rejected(self):
        for field in ('st_dev','st_ino','st_mode','st_size','st_mtime_ns'):
            with self.subTest(field=field):
                changed=self.changed(self.info,**{field:getattr(self.info,field)+1})
                with patch.object(b.os,'fstat',return_value=changed):
                    with self.assertRaisesRegex(ValueError,'changed before read'):
                        b.read_historical_target(self.target,self.info)

    def test_unavailable_file_identity_is_rejected(self):
        for field in ('st_dev','st_ino'):
            with self.subTest(field=field):
                changed=self.changed(self.info,**{field:0})
                with patch.object(b.os,'fstat',return_value=changed):
                    with self.assertRaisesRegex(ValueError,'identity unavailable'):
                        b.read_historical_target(self.target,changed)

    def test_handle_ctime_change_during_read_is_rejected(self):
        changed=self.changed(self.info,st_ctime_ns=self.info.st_ctime_ns+1)
        with patch.object(b.os,'fstat',side_effect=[self.info,changed]):
            with self.assertRaisesRegex(ValueError,'opened target changed during read'):
                b.read_historical_target(self.target,self.info)

    def test_path_ctime_change_after_read_is_rejected(self):
        changed=self.changed(self.info,st_ctime_ns=self.info.st_ctime_ns+1)
        with patch.object(b,'plain_path',return_value=changed):
            with self.assertRaisesRegex(ValueError,'target path changed after read'):
                b.read_historical_target(self.target,self.info)

    def test_same_size_content_change_is_rejected(self):
        self.target.write_bytes(b'Preserved');info=self.target.lstat()
        with self.assertRaisesRegex(ValueError,'content changed'):
            b.read_historical_target(self.target,info)

    def test_open_failure_is_not_success(self):
        with patch.object(Path,'open',side_effect=PermissionError('read blocked')):
            with self.assertRaisesRegex(PermissionError,'read blocked'):
                b.read_historical_target(self.target,self.info)


class StorageScan(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)/'evidence'
        self.root.mkdir()
        self.link=self.root/b.HISTORICAL_LINK
        self.link.parent.mkdir(parents=True)
        self.target=self.root/b.HISTORICAL_TARGET
        self.target.write_bytes(b'preserved')
        (self.root/'ordinary.bin').write_bytes(b'abcde')
        # Windows의 symlink 권한 부족은 PASS가 아니라 명시 skip. 현장 필수 검증은 skip 0 요구.
        try:self.link.symlink_to(self.target)
        except OSError as error:
            if os.name=='nt' and getattr(error,'winerror',None)==1314:
                self.skipTest('Windows file symlink privilege unavailable')
            raise
        self.expiry=int(time.time()*1000)+30000

    def scan(self):
        audit=[]
        size=b.directory_bytes(self.root,self.expiry,historical=True,link_audit=audit)
        return size,audit

    def test_registered_link_preserved_target_counted_once_and_audited(self):
        before=self.link.lstat()
        size,audit=self.scan()
        self.assertEqual(14+before.st_size,size)
        self.assertEqual(1,len(audit))
        self.assertFalse(audit[0]['followed'])
        self.assertEqual(str(b.HISTORICAL_TARGET),audit[0]['targetCountedAt'])
        self.assertEqual(9,audit[0]['targetLogicalBytes'])
        self.assertEqual(b.file_identity(before),b.file_identity(self.link.lstat()))
        self.assertEqual(str(self.target),str(Path(os.readlink(self.link).removeprefix('\\\\?\\'))))

    def test_default_scanner_still_rejects_the_same_link(self):
        with self.assertRaisesRegex(ValueError,'Storage reparse child'):
            b.directory_bytes(self.root,self.expiry)

    def test_unregistered_link_rejected_even_in_evidence(self):
        (self.root/'other-link').symlink_to(self.target)
        with self.assertRaisesRegex(ValueError,'Storage reparse child'):self.scan()

    def test_retarget_outside_root_rejected(self):
        self.link.unlink();outside=Path(self.temp.name)/'outside';outside.write_bytes(b'no')
        self.link.symlink_to(outside)
        with self.assertRaisesRegex(ValueError,'target changed'):self.scan()

    def test_relative_target_rejected(self):
        self.link.unlink();self.link.symlink_to('unrelated-target')
        with self.assertRaisesRegex(ValueError,'must be absolute'):self.scan()

    def test_historical_target_same_size_content_change_rejected(self):
        self.target.write_bytes(b'Preserved')
        with self.assertRaisesRegex(ValueError,'content changed'):self.scan()

    def test_missing_link_or_regular_replacement_rejected(self):
        self.link.unlink()
        with self.assertRaisesRegex(ValueError,'fixture missing'):self.scan()
        self.link.write_bytes(b'not-a-link')
        with self.assertRaisesRegex(ValueError,'fixture missing'):self.scan()

    def test_missing_or_reparse_target_rejected(self):
        self.target.unlink()
        with self.assertRaises(FileNotFoundError):self.scan()
        outside=Path(self.temp.name)/'outside';outside.write_bytes(b'abc')
        self.target.symlink_to(outside)
        with self.assertRaisesRegex(ValueError,'Storage reparse'):self.scan()

    def test_missing_root_under_symlink_parent_is_not_zero(self):
        alias=Path(self.temp.name)/'alias';alias.symlink_to(self.root,target_is_directory=True)
        with self.assertRaisesRegex(ValueError,'Storage reparse path'):
            b.directory_bytes(alias/'absent',self.expiry)
        self.assertEqual(0,b.directory_bytes(Path(self.temp.name)/'absent',self.expiry))

    def test_link_read_failure_is_not_silently_skipped(self):
        with patch.object(b.os,'readlink',side_effect=PermissionError('unreadable link')):
            with self.assertRaisesRegex(PermissionError,'unreadable link'):self.scan()

    def test_link_disappears_during_scan_rejected(self):
        real=b.historical_link
        def remove_after_check(root,path,info):
            result=real(root,path,info);path.unlink();return result
        with patch.object(b,'historical_link',side_effect=remove_after_check):
            with self.assertRaises(FileNotFoundError):self.scan()

    def test_deadline_checked_even_for_absent_root(self):
        with self.assertRaisesRegex(ValueError,'deadline'):
            b.directory_bytes(Path(self.temp.name)/'absent',0)

    def test_only_evidence_group_gets_exception(self):
        with self.assertRaisesRegex(ValueError,'Storage reparse child'):
            b.storage_observation({'work':self.root},{'work':0},self.expiry)
        observed,audit=b.storage_observation({'evidence':self.root},{'evidence':0},self.expiry)
        self.assertEqual(self.scan()[0],observed['evidence']);self.assertEqual(1,len(audit))

    def test_storage_audit_survives_append_and_consumption_does_not_fall(self):
        ledger=Path(self.temp.name)/'ledger';ledger.mkdir()
        (ledger/'cap-budget.json').write_text(json.dumps(seed()))
        anchor=Path(self.temp.name)/'anchor.json';b.initialize_chain(ledger,anchor)
        marker=(ledger/'cap-storage-initialized.json').read_bytes()
        state,previous=b.load_chain(ledger)
        size,audit=self.scan()
        observed=dict(state['observed'],evidence=state['observed']['evidence']+size)
        result=b.advance(state,observed,{'C':40*b.GIB,'F':400*b.GIB},previous)
        result['storageScan']={'version':3,'historicalLinks':audit}
        b.append_record(ledger,result)
        loaded,previous=b.load_chain(ledger)
        self.assertEqual(audit,loaded['storageScan']['historicalLinks'])
        self.assertEqual(758736115+size,loaded['charged']['C'])
        self.assertEqual(state['pastAllowances'],loaded['pastAllowances'])
        next_result=b.advance(loaded,dict(observed,evidence=observed['evidence']-1000),loaded['free'],previous)
        b.append_record(ledger,next_result)
        self.assertEqual(loaded['charged'],b.load_chain(ledger)[0]['charged'])
        self.assertEqual(marker,(ledger/'cap-storage-initialized.json').read_bytes())

@unittest.skipUnless(os.name=='nt','Windows junction fixture required')
class WindowsJunction(unittest.TestCase):
    def test_windows_junction_not_accepted_as_historical_symlink(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)/'evidence';link=root/b.HISTORICAL_LINK
            link.parent.mkdir(parents=True)
            (root/b.HISTORICAL_TARGET).write_bytes(b'preserved')
            target=Path(directory)/'junction-target';target.mkdir()
            result=subprocess.run(['cmd.exe','/d','/c','mklink','/J',str(link),str(target)],
                                  capture_output=True,timeout=10)
            self.assertEqual(0,result.returncode,result.stderr.decode(errors='replace'))
            try:
                with self.assertRaisesRegex(ValueError,'symbolic link|reparse tag'):
                    b.directory_bytes(root,int(time.time()*1000)+30000,historical=True)
            finally:
                os.rmdir(link)


if __name__=='__main__':unittest.main()
