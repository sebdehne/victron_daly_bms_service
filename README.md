# Daly BMS Service

Small program that reads out all data from a Daly BMS (Battery Management System),
at a configurable time interval, and publishes the data to a MQTT broker.

There are already lots of other tools out there with similar functionality,
but this tool is able to cope with multiple Daly BMSes. Just give
it a list of USB device ids (the id of the USB<->UART adapter), and it
will automatically find the associated serial device (e.g. /dev/ttyUSB? on linux)
and start reading data from it. It is possible to add, remove BMSes without
restarting the tool.

The tool is meant to run as a service. If the serial port connection is
lost, it will automatically reconnect.

To prevent the Daly BMS from sleeping (and thus not respoding to data read requests)
just set the sleep time to 65536 via the PC software once.

## Configuration file

TODO
