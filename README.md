# Bike Fan Speed Control

This is an Android app which reads ANT+ bike speed sensor in the background, and set fan speed automatically based on the speed.

I use this when riding indoor on a bike trainer with my ANT+ enabled Samsung phone.

# Setup

I ran my own simple [httpd server](https://github.com/starryalley/go-servotester/tree/master/cmd/pi-servo-httpd) on a RPi4 connecting to a RC servo, which then controls the rotary knob on a cheap fan in front of my bike. 



See my [go-servotester](https://github.com/starryalley/go-servotester/tree/master/cmd/pi-servo-httpd) project for more detail about RC servo control.


In addition to this app, I also run Zwift Android app on the same phone, reading the same bike speed sensor from my bike. 

# Android app

I have to create a [Foreground Service](https://developer.android.com/guide/components/services.html#Foreground) because Zwift app (not the companion) obviously puts a lot of stress on my 3-year-old S8 and the runtime keeps on killing my Service. 

# RC Servo

I use an analog RC servo retired from my 1/8 electric buggy, so it is quite strong without taking too much current from the 5v pins on my RPi4. 

# Fan and Bike trainer

Luckily I got this old fan besides the road dumped by someone else in my neighborhood. It is still usable.

I have a 13+ years old Cycleops Fluid2 so I have to attach a speed sensor on it for Zwift. Sadly I don't have power meter either. The ANT+ speed sensor is an old Garmin one. Without a USB ANT+ stick, this leaves me with an only choice to use my ANT+ enabled Samasung phone to control the fan. 

# Raspberry Pi

For ease of use (and personal use only) I created a HTTP server so I can simply send a HTTP GET to the endpoint, specifying the correct pin (I use pin 11) to control the RC servo.