# Victron Daly BMS Service

Small program that reads out data from multiple Daly BMS (Battery Management System), 
combines this data into one virtual battery, and publishes the parameters to the Victron 
via MQTT. It depends on the [dbus-mqtt-services](https://github.com/sebdehne/dbus-mqtt-services) 
driver to be installed on the Victron side, which transforms the MQTT messages to a service
on the dbus.

This tool is meant to run on a linux device (separate from the Victron cerbo gx) with enough 
USB ports to support one USB/UART connection per BMS. Personally, I am using a Raspberry PI. 

There are already lots of other tools out there with similar functionality,
but this tool is able to cope with multiple Daly BMSes, which is what I needed. 
It is meant to run as a service and will automatically re-connect the MQTT-connection and/or all
serial port connections upon communications errors.

To prevent the Daly BMS from sleeping (and thus not respoding to data read requests)
just set the sleep time to 65536 via the PC software once.

