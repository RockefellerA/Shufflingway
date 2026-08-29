<a href="https://ko-fi.com/rockefellera" target="_blank">
  <img width="142" height="18" alt="image" src="https://github.com/user-attachments/assets/deae1d46-12c9-4e5a-9e97-fa2002b94a8f" />
</a>


# <img width="64" height="62" alt="shufflingway icon" src="https://github.com/user-attachments/assets/16c919c4-2701-4751-b325-303133e9ff52" /> Shufflingway

Lightweight FFTCG client and fan project.

Includes a deck manager, full card browser, game board and CPU opponent.

Links to rules and guides are found in the Help menu.

<img width="2455" height="1378" alt="Shufflingway2" src="https://github.com/user-attachments/assets/4eeb7d69-f67e-4caf-86aa-c186eaf16a86" />
<img width="2457" height="1385" alt="Shufflingway1" src="https://github.com/user-attachments/assets/5bc5b9ff-b8d1-43e4-ac5d-2b2bbf630fc2" />

The ultimate goal for this application will be to play against someone else with the application facilitating the flow of the game, tracking the game state and resolving card interactions.

Current card coverage (estimate):

* Action/Special Abilities: **(100%)**
* Auto Abilities: (94.1%)
* Field Abilities: **(100%)**
* Summons: (94.2%)

# Installation Guide

Use the 'Releases' section on the right to download the latest version.

* .msi: Windows Installer
* .dmg: MacOS 13+
* .deb: Linux

Once the application has been installed, you can update to any new releases from "Check for Updates..." in the Help menu.

# Quick Start Guide

* Open the Card Browser to initiate fetching card data from the official API.  
* After this completes, create a deck in the Deck Manager. Various preconstructed decks can be loaded via a button at the top.
* Once a 50-card deck has been created, you can either create another 50-card deck for the CPU, or do a mirror match.
* P2P Multiplayer has been implemented - once both players have chosen a deck, the host clicks "Start Game". Please reach out with any bugs found!

# Notes:

* Opus 29 is now live! Cards can be fetched via the Card Browser card update button. New overpayment rules have been implemented.
* Next features (aside from parsing additions and bugfixes):
  1. Additional animations for better gameplay context
  2. More trait icons for various card states (Shielded, must attack, cannot block, etc.)
