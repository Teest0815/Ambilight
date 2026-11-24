# Ambilight für Android TV + WLED

Android-TV-App, die den Bildschirm aufnimmt und daraus ein Ambilight für einen WLED-LED-Streifen hinter dem Fernseher berechnet.

## Funktionen

- **Aufnahme** per MediaProjection in niedriger Auflösung (80 bis 320 px), Aufnahmefrequenz einstellbar (5 bis 60 FPS)
- **Senden per DDP** (Port 4048) oder **UDP Realtime DNRGB** (Port 21324, auch für Streifen mit über 490 LEDs)
- **LED-Layout frei einstellbar**: LEDs oben/rechts/unten/links, Startecke, Laufrichtung, Versatz, übersprungene LEDs am Anfang, Gesamtzahl, Messtiefe am Bildrand
- **Live-Vorschau** des Layouts mit Markierung der ersten LED und Layout-Test (laufender weißer Punkt) zum Prüfen von Reihenfolge und Richtung
- **Schwarze Balken** werden automatisch erkannt (oben/unten und links/rechts) und ignoriert, mit einstellbarer Schwelle, Verzögerung, maximaler Balkenbreite und optional symmetrischer Erkennung (weniger Fehlerkennung in dunklen Szenen)
- **Verhalten bei schwarzem Bild** (z. B. Netflix mit Kopierschutz): LEDs aus, letzte Farben halten, feste Farbe, Effekt der App (Atmen, Regenbogen, Farbwechsel, Komet, Funkeln) oder WLED übernimmt mit eigenem Preset bzw. Effekt (inkl. Geschwindigkeit, Intensität, Palette)
- **Farbanpassung**: Durchschnitt oder kräftige Farben bevorzugen, Glättung, Helligkeit, Sättigung, Gamma, Weißabgleich (R/G/B)
- **WLED automatisch finden** (mDNS und Netzwerk-Scan), Adresse und LED-Anzahl werden übernommen
- **Standby** (Bildschirm aus, optional auch Bildschirmschoner): Das Ambilight pausiert, und du wählst, was dann passiert: WLED aus, WLED übernimmt mit eigenem Preset oder Effekt (inkl. Geschwindigkeit, Intensität, Palette), feste Farbe oder ein App-Effekt. Beim Aufwachen geht es automatisch weiter.
- **Automatische Leistungsanpassung**: Bildrate und Auflösung werden gesenkt, wenn das Gerät nicht mitkommt (die eigenen Einstellungen sind die Obergrenze)
- WLED beim Start ein- und beim Beenden ausschalten, LED-Anzahl direkt aus WLED lesen, nach einem WLED-Neustart wird es wieder eingeschaltet
- Optional Start nach dem Einschalten des Fernsehers

## Einrichtung

1. APK installieren (siehe unten) und App öffnen.
2. „WLED automatisch suchen“ drücken und das Gerät auswählen (oder die WLED-Adresse von Hand eintragen).
3. LED-Anzahl pro Seite, Startecke und Laufrichtung einstellen. Mit „LED-Layout testen“ prüfen: Oben leuchtet rot, rechts grün, unten blau, links gelb, und ein weißer Punkt läuft vom ersten bis zum letzten LED.
4. „Ambilight starten“ und die Berechtigung zur Bildschirmaufnahme bestätigen.

## APK bauen

Bei jedem Push baut GitHub Actions eine signierte Release-APK (`.github/workflows/build.yml`). Sie liegt danach unter **Actions → letzter Lauf → Artifacts → Ambilight-apk** (als ZIP, darin `app-release.apk`).

Die APK wird immer mit demselben Schlüssel (`app/ambilight-release.jks`) signiert. Neue Versionen lassen sich deshalb direkt über die alte installieren, Einstellungen bleiben erhalten. Der Schlüssel liegt im privaten Repository. Wird das Repo öffentlich, bitte einen neuen Schlüssel erzeugen und die Passwörter als GitHub-Secrets hinterlegen.

## Bekannte Grenzen

- Inhalte mit Kopierschutz (Netflix, Disney+, Prime Video …) liefern der App ein schwarzes Bild. Genau dafür gibt es die Einstellungen unter „Schwarzes Bild“.
- Nur was auf dem Fernseher selbst läuft, wird aufgenommen. HDMI-Eingänge sind für Apps nicht zugänglich.
- Im Standby läuft „WLED übernimmt“ am zuverlässigsten, weil WLED den Effekt selbst abspielt. Feste Farbe und App-Effekt brauchen, dass die App im Standby weiterläuft, was nicht jeder Fernseher erlaubt.
- Die Berechtigung zur Bildschirmaufnahme muss nach jedem Start bestätigt werden. „Beim Start des Fernsehers starten“ öffnet nur die App und zeigt diese Abfrage, manche Geräte blockieren das.
- Der Code ist bisher nicht auf einem echten Fernseher getestet.
