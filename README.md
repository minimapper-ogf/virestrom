# Virestrom
Virestrom is a simple building tag tool designed for OGF:Mishota. 

## Installation
Download the .jar file in releases and navigate it to your JOSM plugins folder. [More Info](https://wiki.openstreetmap.org/wiki/JOSM/Plugins#Manually_install_JOSM_plugins)

## Developing 
(more or less just personal notes)
For Building:

Modify manifest.txt with version.
Run the following:

``` bash
javac -classpath ../josm-tested.jar -d bin src/org/openstreetmap/josm/plugins/virestrom/*.java

jar cfm dist/virestrom.jar manifest.txt -C bin .

cp dist/virestrom.jar ~/.var/app/org.openstreetmap.josm/data/JOSM/plugins/
```
