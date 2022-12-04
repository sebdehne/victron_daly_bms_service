# Victron Daly BMS Service

Small program that reads out data from multiple Daly BMS (Battery Management System), 
combines this data into one virtual battery, and publishes the parameters to the Victron
via MQTT. This enables full integration of Daly BMS with Victron.

This tool is meant to run on a linux device (separate from the Victron cerbo gx) with enough 
USB ports to support one USB/UART connection per BMS. Personally, I am using a Raspberry PI. 

There are already lots of other tools out there with similar functionality,
but this tool is able to cope with multiple Daly BMSes. Just give
it a list of USB device ids (the id of the USB<->UART adapter), and it
will automatically find the associated serial device (e.g. /dev/ttyUSB? on linux)
and start reading data from it. This makes it possible to distinguish between the BMSes
and know which BMS provides which data. An id and a display name can be associated with 
the BMS to make it easier upstream and processing the data.

It is also possible to add/remove BMSes at runtime in the configuration file without restart.

The tool is meant to run as a service. If the serial port connection is
lost, it will automatically reconnect.

To prevent the Daly BMS from sleeping (and thus not respoding to data read requests)
just set the sleep time to 65536 via the PC software once.

## Configuration file

TODO
