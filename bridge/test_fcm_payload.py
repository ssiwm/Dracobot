import sys
import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import fcm_bridge
from fcm_payload import notification_data


class NotificationPayloadTest(unittest.TestCase):
    def test_payload_carries_exact_profile_and_durable_session(self):
        self.assertEqual(
            {"profile": "architect", "stored_session_id": "stored-successor", "message_id": "41"},
            notification_data(" architect ", " stored-successor ", " 41 ")
        )

    def test_incomplete_route_falls_back_without_partial_target(self):
        self.assertEqual({}, notification_data("architect", "", "41"))
        self.assertEqual({}, notification_data("", "stored-successor", "41"))
        self.assertEqual({}, notification_data("architect", "stored-successor", ""))


class BridgePollDeliveryTest(unittest.TestCase):
    def test_poll_reads_durable_database_session_id_and_passes_it_to_fcm(self):
        with tempfile.TemporaryDirectory() as tmp:
            db_path = Path(tmp) / "state.db"
            con = sqlite3.connect(db_path)
            try:
                con.executescript(
                    """
                    CREATE TABLE sessions (id TEXT PRIMARY KEY, source TEXT);
                    CREATE TABLE messages (
                        session_id TEXT, id INTEGER, timestamp REAL, content TEXT,
                        role TEXT, active INTEGER, observed INTEGER
                    );
                    INSERT INTO sessions VALUES ('stored-successor', 'android-app');
                    INSERT INTO messages VALUES (
                        'stored-successor', 41, 0, 'Durable result', 'assistant', 1, 0
                    );
                    """
                )
                con.commit()
            finally:
                con.close()

            deliveries = []
            with patch.object(fcm_bridge, "BOTS", ["architect"]), \
                patch.object(fcm_bridge, "profile_db", return_value=str(db_path)), \
                patch.object(fcm_bridge, "app_is_open", return_value=False), \
                patch.object(fcm_bridge, "fcm_send", side_effect=lambda title, body, data: deliveries.append((title, body, data))), \
                patch.object(fcm_bridge.time, "sleep"):
                fcm_bridge._seen.clear()
                fcm_bridge.poll_once()

            self.assertEqual(
                [("🤖 architect", "Durable result", {
                    "profile": "architect",
                    "stored_session_id": "stored-successor",
                    "message_id": "41",
                })],
                deliveries
            )

    def test_poll_emits_only_aggregate_health_without_message_or_route_values(self):
        with tempfile.TemporaryDirectory() as tmp:
            db_path = Path(tmp) / "state.db"
            con = sqlite3.connect(db_path)
            try:
                con.executescript(
                    """
                    CREATE TABLE sessions (id TEXT PRIMARY KEY, source TEXT);
                    CREATE TABLE messages (
                        session_id TEXT, id INTEGER, timestamp REAL, content TEXT,
                        role TEXT, active INTEGER, observed INTEGER
                    );
                    INSERT INTO sessions VALUES ('stored-private', 'android-app');
                    INSERT INTO messages VALUES (
                        'stored-private', 42, 0, 'private response body', 'assistant', 1, 0
                    );
                    """
                )
                con.commit()
            finally:
                con.close()

            printed = []
            with patch.object(fcm_bridge, "BOTS", ["private-profile"]), \
                patch.object(fcm_bridge, "profile_db", return_value=str(db_path)), \
                patch.object(fcm_bridge, "app_is_open", return_value=False), \
                patch.object(fcm_bridge, "fcm_send", return_value=True), \
                patch.object(fcm_bridge.time, "sleep"), \
                patch("builtins.print", side_effect=lambda *values, **_: printed.append(" ".join(map(str, values)))):
                fcm_bridge._seen.clear()
                result = fcm_bridge.poll_once()

            self.assertEqual(1, result.candidates)
            self.assertEqual(1, result.attempted)
            self.assertEqual(1, result.sent)
            self.assertEqual(0, result.failed)
            self.assertFalse(result.suppressed_for_open_app)
            output = "\n".join(printed)
            self.assertNotIn("private response body", output)
            self.assertNotIn("private-profile", output)
            self.assertNotIn("stored-private", output)

    def test_open_app_poll_is_suppressed_without_reading_candidate_messages(self):
        with patch.object(fcm_bridge, "app_is_open", return_value=True), \
            patch.object(fcm_bridge, "fetch_new_assistant_messages") as fetch, \
            patch("builtins.print"):
            result = fcm_bridge.poll_once()

        self.assertTrue(result.suppressed_for_open_app)
        self.assertEqual(0, result.candidates)
        self.assertEqual(0, result.attempted)
        fetch.assert_not_called()


if __name__ == "__main__":
    unittest.main()
