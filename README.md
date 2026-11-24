# Ambilight für Android TV + WLED

Android-TV-App, die den Bildschirm aufnimmt und daraus ein Ambilight für einen WLED-LED-Streifen hinter dem Fernseher berechnet.

## Bedienung

Auf dem Startbildschirm siehst du links die Vorschau, den Status und die Knöpfe zum Starten und Testen. Rechts öffnet jede Kachel (eine pro Zeile, alle gleich hoch) einen eigenen Einstellungsbereich: WLED-Verbindung, LED-Layout, Aufnahme, Farbe & Glättung, Schwarze Balken, Schwarzes Bild, Standby und System. Alles lässt sich mit der Fernbedienung bedienen, die fokussierte Kachel ist orange umrandet.

## Funktionen

- **Aufnahme** per MediaProjection in niedriger Auflösung (80 bis 320 px), Aufnahmefrequenz einstellbar (5 bis 60 FPS)
- **Senden per DDP** (Port 4048) oder **UDP Realtime DNRGB** (Port 21324, auch für Streifen mit über 490 LEDs)
- **LED-Layout frei einstellbar**: LEDs oben/rechts/unten/links, Startecke, Laufrichtung, Versatz, übersprungene LEDs am Anfang, Gesamtzahl, Messtiefe am Bildrand
- **Live-Vorschau** des Layouts mit Markierung der ersten LED und Layout-Test (laufender weißer Punkt) zum Prüfen von Reihenfolge und Richtung
- **Schwarze Balken** werden automatisch erkannt (oben/unten und links/rechts) und ignoriert, mit einstellbarer Schwelle, Verzögerung, maximaler Balkenbreite und optional symmetrischer Erkennung (weniger Fehlerkennung in dunklen Szenen)
- **Verhalten bei schwarzem Bild** (z. B. Netflix mit Kopierschutz): LEDs aus, letzte Farben halten, feste Farbe, Effekt der App (Atmen, Regenbogen, Farbwechsel, Komet, Funkeln) oder WLED übernimmt mit eigenem Preset bzw. Effekt (inkl. Geschwindigkeit, Intensität, Palette)
- **Weiche Übergänge** zwischen Ambilight, schwarzem Bild und Standby (Dauer einstellbar, 0 = sofort). Bei „WLED übernimmt“ wird zuerst auf Schwarz überblendet und dann der WLED-Effekt gestartet (mit WLED-eigener Übergangszeit), beim Zurückwechseln blendet das Ambilight aus Schwarz ein
- **LED-Test** (Knopf „LEDs testen“): Layout-Test, Dimmen (Weiß, Rot, Grün, Blau), ruckartig wechselnde Farben, RGB-Rad oder alles nacheinander. Der Knopf stoppt den Test jederzeit
- **Übersichtliche Einstellungen**: In „Schwarzes Bild“ und „Standby“ werden nur die Einstellungen gezeigt, die zur gewählten Aktion passen (z. B. bei „WLED übernimmt“ nur die WLED-Werte, und bei gewähltem Preset nicht die Effekt-ID)
- **Farbanpassung**: Durchschnitt oder kräftige Farben bevorzugen, Glättung, Helligkeit, Sättigung, Gamma, Weißabgleich (R/G/B)
- **WLED automatisch finden** (mDNS und Netzwerk-Scan), Adresse und LED-Anzahl werden übernommen
- **Standby** (Bildschirm aus, optional auch Bildschirmschoner): Das Ambilight pausiert, und du wählst, was dann passiert: WLED aus, WLED übernimmt mit eigenem Preset oder Effekt (inkl. Geschwindigkeit, Intensität, Palette), feste Farbe oder ein App-Effekt. Beim Aufwachen geht es automatisch weiter.
- **Automatische Leistungsanpassung**: Bildrate und Auflösung werden gesenkt, wenn das Gerät nicht mitkommt (die eigenen Einstellungen sind die Obergrenze)
- WLED beim Start ein- und beim Beenden ausschalten, LED-Anzahl direkt aus WLED lesen, nach einem WLED-Neustart wird es wieder eingeschaltet
- Optional Start nach dem Einschalten des Fernsehers

## Einrichtung

1. APK installieren (siehe unten) und App öffnen.
2. Kachel **WLED-Verbindung** öffnen, „WLED automatisch suchen“ drücken und das Gerät auswählen (oder die WLED-Adresse von Hand eintragen).
3. Kachel **LED-Layout** öffnen und LED-Anzahl pro Seite, Startecke und Laufrichtung einstellen. Zurück auf dem Startbildschirm: Mit „LEDs testen“ → „Layout“ prüfen: Oben leuchtet rot, rechts grün, unten blau, links gelb, und ein weißer Punkt läuft vom ersten bis zum letzten LED.
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
