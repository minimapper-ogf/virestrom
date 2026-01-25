# Virestrom
Virestrom is a simple building tag tool designed for OGF:Mishota. 

## Installation
Download the .jar file in releases and navigate it to your JOSM plugins folder. Info on where your plugins folder is can be found [here](https://wiki.openstreetmap.org/wiki/JOSM/Plugins#Manually_install_JOSM_plugins).

## Development
I dont intend others to add to this but if you want to heres a few quick commands such as building and easially moving the file to your plugins folder :)

Modify manifest.txt with version.
Run the following for building:

``` bash
javac -classpath ./josm-tested.jar -d bin src/org/openstreetmap/josm/plugins/virestrom/*.java

jar cfm dist/virestrom.jar manifest.txt -C bin .
```

Move the .jar to JOSM's plugins folder:

### MacOS
``` bash
cp dist/virestrom.jar ~/Library/JOSM/plugins/
```

### Windows
``` bash
copy dist\virestrom.jar %APPDATA%\JOSM\plugins\
```

### Linux Standalone
``` bash
cp dist/virestrom.jar ~/.local/share/JOSM/plugins/
```

### Flatpak
``` bash
cp dist/virestrom.jar ~/.var/app/org.openstreetmap.josm/data/JOSM/plugins/
```
