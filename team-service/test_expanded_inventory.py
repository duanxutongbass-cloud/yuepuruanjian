"""Capacity-sized inventories must stay exact and proportional to occupied rows."""
import sqlite3
import unittest

from expanded_protocol import ExpandedCatalog
from number_inventory import inventory
from service import Rejected


class ExpandedInventoryTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.addCleanup(self.db.close)
        self.db.row_factory = sqlite3.Row
        self.db.executescript('''
            CREATE TABLE artifact_metadata(number TEXT);
            CREATE TABLE artifact_tombstones(number TEXT);
            CREATE TABLE metadata(key TEXT, value TEXT);
            INSERT INTO metadata VALUES('catalogRevision', '7');
        ''')

    def test_full_legacy_range_uses_1000_and_later_gaps_without_changing_rows(self):
        self.db.executemany('INSERT INTO artifact_metadata VALUES(?)',
                            [(f'0101{n:03d}',) for n in range(1, 1000)])
        self.db.execute("INSERT INTO artifact_tombstones VALUES('01-01-1001')")
        before = tuple(self.db.iterdump())
        result = ExpandedCatalog.number_inventory(self.db, 0)
        self.assertEqual('1000', result['firstFreeSequence'])
        self.assertEqual('9223372036854774807', result['availableCount'])
        self.assertEqual(before, tuple(self.db.iterdump()))
        self.db.execute("INSERT INTO artifact_metadata VALUES('01-01-1000')")
        self.assertEqual('1002', ExpandedCatalog.number_inventory(self.db, 0)['firstFreeSequence'])

    def test_duplicate_parts_and_retirements_count_one_sequence_and_sort_exactly(self):
        self.db.executemany('INSERT INTO artifact_metadata VALUES(?)',
            [('0101001',), ('0201001',), ('01-01-10000',), ('01-01-9999',),
             ('01-01-9223372036854775807',)])
        self.db.execute("INSERT INTO artifact_tombstones VALUES('0301001')")
        result = ExpandedCatalog.number_inventory(self.db, 0)
        self.assertEqual(['001', '9999', '10000', '9223372036854775807'], result['occupiedSequences'])
        self.assertEqual('002', result['firstFreeSequence'])
        self.assertEqual('9223372036854775803', result['availableCount'])
        with self.assertRaises(Rejected):
            inventory(self.db, 0)

    def test_invalid_or_overflow_retirement_fails_closed(self):
        for number in ('01-01-9223372036854775808', '01011000', '01-01-01000', '０1-01-1000'):
            with self.subTest(number=number):
                self.db.execute('DELETE FROM artifact_tombstones')
                self.db.execute('INSERT INTO artifact_tombstones VALUES(?)', (number,))
                with self.assertRaises(Rejected):
                    ExpandedCatalog.number_inventory(self.db, 0)
