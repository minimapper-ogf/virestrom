# Virestrom
Virestrom is a simple tagging tool desgined for OGF:Mishota
More documentation can be found [here](https://minimapper.net/docs/virestrom.html)

## Features
Features include: 
- Building Tagger
- Admin Level Tagger

All features can be found in tools.

### Building Tagging
With a area seclected, you can use Create Building Tags or CTRL+SHIFT+X and a popup will appear.
From the popup you can choose things such as building type, the target number of residents you want (picks a random value around your target and with more buildings the closer the average), and address. For apartments you can choose unit size and floor count and it does the math to how many units there are and how many people live in the building using the targer residents.

### Admin Level Tagging
With a area seclected (or group of ways to make an area seclected), you can use Create Admin Bound Tags or ALT+SHIFT+Q
From the popup you can choose name, population, admin level, what county its in, and what state its in. Admin level and what county its in are saved during the session to save filling out fields. Is_in:state saves acrost sessions.

#### Extra Notes
Admin Level Tagging still does not handle inside ways. I wanted it just to ease my area tagging process not for the relation building which I am better doing manually.

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
