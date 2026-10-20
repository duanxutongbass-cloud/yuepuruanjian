import unittest

from score_organizer.domain.numbering import (
    SequenceExhaustedError,
    next_global_sequence,
    normalize_seq,
)


class NumberingTests(unittest.TestCase):
    def test_new_song_fills_first_free_global_number_across_categories(self):
        self.assertEqual(next_global_sequence(["001", "007", "002"]).value, "003")

    def test_sequence_cannot_exceed_999(self):
        with self.assertRaises(SequenceExhaustedError):
            next_global_sequence([f"{i:03d}" for i in range(1, 1000)])

    def test_sequence_must_be_three_digit_range(self):
        self.assertEqual(normalize_seq("1"), "001")
        self.assertEqual(normalize_seq("099"), "099")
        with self.assertRaises(ValueError):
            normalize_seq("000")
        with self.assertRaises(ValueError):
            normalize_seq("1000")


if __name__ == "__main__":
    unittest.main()
