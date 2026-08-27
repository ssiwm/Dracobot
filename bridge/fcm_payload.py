"""Pure FCM routing payload contract shared by the bridge's delivery path."""


def notification_data(
    profile: str,
    stored_session_id: str,
    message_id: str,
) -> dict[str, str]:
    normalized_profile = profile.strip()
    normalized_session_id = stored_session_id.strip()
    normalized_message_id = message_id.strip()
    if not normalized_profile or not normalized_session_id or not normalized_message_id:
        return {}
    return {
        "profile": normalized_profile,
        "stored_session_id": normalized_session_id,
        "message_id": normalized_message_id,
    }
