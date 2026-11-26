# BMW Dash

A smart dash cam for a foldable Android phone (built for the Galaxy Z Fold 7). Not affiliated with BMW.

## Install
Download `BMW-Dash.apk` from the latest [release](../../releases/latest), open it on the phone and allow installing from that source.

## Features
- Rear-camera AI: vehicles, people, bikes, traffic lights and stop signs, with estimated distance and speed
- Lane overlay, lane position, departure and wrong-side warnings
- GPS speed, posted speed limit, road name and lane count
- Live road speed, traffic layer and incidents (free TomTom key, entered in Setup)
- 3D map, destination search, alternate routes and spoken turns
- Weather hazards, loop recording with clip lock and impact lock
- Layouts for the cover screen and the unfolded screen

- Make and model of the car ahead (your own Gemini or Claude key), with estimated speed and gap
- Collision, cut-in, pedestrian and object warnings with a full-screen alarm (the app cannot brake the car)
- Closure, incident, slow-traffic and speed-camera warnings, plus your own police and pothole reports
- Night view, crash prompt with one-tap 911 or text to a contact, break reminders and a trip safety score
- Voice and live destination search, with a focused 3D guidance view
- 3D route arrows and turn signs drawn on the camera picture; Map button for small, large or hidden map; 3D route on/off button

## First drive
1. Mount the phone in landscape behind the windscreen with a clear view, where it does not block yours.
2. While parked, tap **Aim** and line up the horizon, lane centre and hood.
3. Tap **Setup** and paste a TomTom key from my.tomtom.com for live traffic.

Videos are saved to `Movies/BMWDash`. Recording runs while the app is on screen and has no audio.

## Limits
This is a driver aid, not a safety system. Distance, other vehicles' speed and lane readings are
single-camera estimates and get worse at night, in rain and on poorly marked roads.

## Data
OpenStreetMap contributors (Overpass, Nominatim), OpenFreeMap, OSRM, Open-Meteo, TomTom, Google MediaPipe.
Your location is sent to these services to fetch road information. If you add an AI key, pictures of the car ahead are sent to that provider. Search text goes to TomTom, Photon or Nominatim.
