# TWS Print Station

Android Bluetooth Druckstation fuer Odoo Talwiesenschaenke.

Funktion:
- holt nur Rechnungs-Druckjobs von Odoo
- API: https://pos.talwiesenschaenke.de/tws_directprint/jobs?token=TWS8115PRINT
- markiert Jobs nach erfolgreichem Senden als gedruckt
- druckt direkt per Bluetooth ESC/POS auf 80mm Drucker

GitHub Build:
Actions -> Build APK -> Run workflow

APK:
Artifacts -> TWSPrintStation-debug-apk -> app-debug.apk

Wichtig:
- Nur ein Geraet mit Auto-Druck aktiv
- App muss Bluetooth-Berechtigungen bekommen
- Drucker muss vorher mit Android gekoppelt sein
