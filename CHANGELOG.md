# Changelog

All notable changes to this fork are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

### Fixed
- A dictionary that fails to load is no longer re-imported on every key press. Automatic retries now wait 20 minutes, doubling after every failure up to 24 hours, and stop after 5 failures until TT9 restarts. Loading the dictionary manually from Settings still works at any time and resets the wait.
- The first key press after TT9 checks for a dictionary update is no longer swallowed.
