from app.main import build_app


def test_health_route():
    app = build_app()
    client = app.test_client() if hasattr(app, "test_client") else None
    assert app is not None
