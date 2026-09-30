# Changelog

All notable changes to this fork are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

### Fixed
- A dictionary that fails to load is no longer re-imported on every key press. Automatic retries now wait 20 minutes, doubling after every failure up to 24 hours, and stop after 5 failures until TT9 restarts. Loading the dictionary manually from Settings still works at any time and resets the wait.
- The first key press after TT9 checks for a dictionary update is no longer swallowed.
- TT9 no longer keeps checking every 2 seconds whether it is still the selected keyboard after you leave a text field. Previously these checks piled up with every text field opened and drained the battery.
- Fixed a rare crash, or a dictionary loading twice, when TT9 checks for a missing dictionary right after install and you start typing at the same moment.
