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


if __name__ == "__main__":
    unittest.main()
