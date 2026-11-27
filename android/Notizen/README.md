# Notizen für Android

Native Android-App für den mitgelieferten FastAPI-Notizenserver. Die Oberfläche orientiert sich an Google Keep, bleibt aber eine eigenständige Client-App für diesen Server.

## Funktionen

- Keep-inspiriertes Layout mit Original-Notizfarben, Light-, Dark- und System-Theme, Raster- und Listenansicht.
- Schneller Start: zuerst werden verschlüsselte Room-Cachedaten angezeigt; der System-Splash weicht ohne dauerhaften Ladebildschirm der App. Synchronisation läuft anschließend im Hintergrund.
- Anmeldung mit Benutzername/Passwort oder Magic Link, QR-Scanner für Magic Links und Freigabe einer Browser-Geräteanmeldung.
- Notizen und Checklisten mit Titel, formatierbarem Rich Text (fett, kursiv, unterstrichen, H1/H2), Undo/Redo, Sortierung von Listeneinträgen und automatischem Speichern über das serverseitige OT-WebSocket-Protokoll.
- Bilder und PDFs aus dem System-Dateiauswahldialog anhängen, Bilder ansehen und PDFs herunterladen/öffnen/entfernen. Text, Bilder und PDFs können außerdem über Androids Teilen-Menü als neue Notiz übernommen werden.
- Suche, Filter nach Typ/Label/Anhang/Farbe, Labels, Farben, Mehrfachauswahl, Anheften, Archiv, Papierkorb, Wiederherstellen, Kopieren und endgültiges Löschen.
- Zusammenarbeit an Notizen, Versionsverlauf, Sitzungsverwaltung, Magic-Links, Passwort- und Profilbildänderung, Cache- und Speicherfunktionen.
- Lokale Notizdaten werden mit AES-GCM verschlüsselt; der Datenschlüssel ist durch Android Keystore geschützt. Bilder werden mit authentifiziertem HTTP-Client geladen.

## Bauen

1. JDK 17, Android SDK 35 und Android Studio mit Android Gradle Plugin 8.7.3 verwenden.
2. Das Projekt verwendet Gradle 8.9. Gradle und Android-Abhängigkeiten müssen beim ersten Build verfügbar sein.
3. Projektordner in Android Studio öffnen, Abhängigkeiten synchronisieren und die `app`-Konfiguration starten.
4. Für den Emulator wird im Debug-Build `http://10.0.2.2:8000/` als Serveradresse verwendet. Auf einem Gerät die LAN-Adresse des Servers eintragen. Für den produktiven Einsatz HTTPS verwenden.

Die Android-App erwartet `/api/` und `/ws` unter derselben Basis-URL bzw. demselben Reverse-Proxy-Pfad. Benutzer werden über die Admin-Funktionen des Notizenservers angelegt.

## Hinweise

Die App ist ein nativer Client, keine offizielle Google-Keep-App. Die UI deckt die mobilen Schreib-, Such-, Organisations- und Kontofunktionen des mitgelieferten Servers ab. Einige browser-/admin-spezifische Verwaltungsabläufe bleiben auf der Weboberfläche des Servers.

Ein echter Gradle/Kotlin-Build konnte in der Arbeitsumgebung nicht ausgeführt werden, da weder JDK noch Android SDK installiert waren. Der Quellstand wurde deshalb statisch geprüft; bitte beim ersten Öffnen in Android Studio kompilieren.
