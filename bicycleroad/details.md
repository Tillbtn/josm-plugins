"Fahrradstraße" should add:
bicycle=designated
bicycle_road=yes
traffic_sign=DE:244.1
maxspeed=30
source:maxspeed=DE:bicycle_road
vehicle=no
(image: https://upload.wikimedia.org/wikipedia/commons/b/bf/Zeichen_244_-_Beginn_der_Fahrradstra%C3%9Fe%2C_StVO_1997.svg)

"Fahrradzone" should add
bicycle=designated
bicycle_road=yes
traffic_sign=DE:244.3
maxspeed=30
source:maxspeed=DE:bicycle_zone
vehicle=no
(image: https://upload.wikimedia.org/wikipedia/commons/4/4e/Zeichen_244.3_-_Beginn_einer_Fahrradzone%2C_StVO_2020.svg)

for the additional signs:
"Anlieger frei" should add:
vehicle=destination (replace vehicle=no)
add "1020-30" with a comma to the existing traffic_sign value, i.e. "DE:244.1,1020-30"
(image: https://upload.wikimedia.org/wikipedia/commons/c/c1/Zusatzzeichen_1020-30_-_Anlieger_frei_%28600x330%29%2C_StVO_1992.svg)

"KFZ frei" should add
"motor_vehicle=yes"
add "KFZ frei" to the existing traffic_sign value, i.e. "DE:244.3,"KFZ frei"
traffic_sign:note="Zusatzzeichen: kombiniertes Schild aus 1024-10,1022-12 ohne eigene Nummer"
(image: https://wiki.openstreetmap.org/w/images/c/ce/Zusatzzeichen_KFZ_frei.svg)

"Landwirtschaftlicher Verkehr frei" should add:
vehicle=agricultural (replace vehicle=no)
add "1026-36" to the traffic_sign
(image: https://upload.wikimedia.org/wikipedia/commons/4/41/Zusatzzeichen_1026-36_-_Landwirtschaftlicher_Verkehr_frei_%28450x600%29%2C_StVO_1992.svg)

"Fortwirtschaftlicher Verkehr frei" should add:
vehicle=forestry (replace vehicle=no)
add "1026-37" to the traff_sign
(image: https://upload.wikimedia.org/wikipedia/commons/d/d6/Zusatzzeichen_1026-37_-_Forstwirtschaftlicher_Verkehr_frei%2C_StVO_1992.svg)

"Land- und Forstwirtschaftlicher Verkehr frei" should add:
vehicle=agricultural;forestry (replace vehicle=no)
add "1026-38" to the traffic_sign
(image: https://upload.wikimedia.org/wikipedia/commons/6/6b/Zusatzzeichen_1026-38_-_Land-_und_forstwirtschaftlicher_Verkehr_frei_%28450x600%29%2C_StVO_1992.svg)

"Linienverkehr frei" should add:
bus=yes
add "1026-32" to the traffic_sign
(image: https://upload.wikimedia.org/wikipedia/commons/1/14/Zusatzzeichen_1026-32_-_Linienverkehr_frei_%28450x600%29%2C_StVO_1992.svg)