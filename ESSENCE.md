# Aurora.local - Essence

# WTF is an essence?

A smell, a taste, a colour, a feeling or an idea; it's a kernel of something that makes a thing different. 

This is Aurora.local's essence file, a human written set of guidelines and thoughts on how this project should be. 

The essence file should **never** be edited or updated by LLMs or agents. it can only be edited by humans.

If edits or changes are made to this file it will fail the build.

# Story

_Every project has a story and this should be documented here in two to three sentences._

Aurora.local started as a hacky collection of ansible scripts, docker compose files and lots of shell scripts
to run some services locally in a homelab environment.

After some time it made sense to build a control panel and management service ontop of this and strip out 
some of the scripts.

When more apps were needed some core services started to be identified like auth, email, backups. These core 
services support other services to reduce duplicated configurations.

# Who is it for?

You will need some basic know how of computers, using command line tools, networks and understanding how hosting 
works.

# Goals

- Make self hosting easy in a local environment
- 99% done in a web UI, 1% on the terminal
- Docker is our friend and should be used entirely
- Core apps should never be swapped 
- Keep simple configurations easy to manage through Aurora.local
- AI is encouraged to improve and maintain this tool
- Opinionated installs in this project should save time and stress to the user primarily. Anything else is a bonus

# Non-Goals

- This should never become a paid or enterprise tool
- This should not become a swiss army knife with multiple alternatives for x,y and z. Just one solid choice for each area
- Optimisations are good but heavy re-architectural changes or UI is not wanted
- Terminal and SSH setup should be minimised

# Aurora's Core

Aurora comes packaged with "core" tools and apps that makes life easier. There is ofcourse opinions on these tools
with advantages and disadvantages. Aurora uses popular and some more modern niche tools.

- Email service: Stalwart 
- Authentication and SSO: Authelia
- Networking and Reverse proxy: Caddy

If a core app is changed or swapped out in Aurora, then that is a big thing. It might not break anything
but this should be discussed and agreed upon. 

# Aurora's Apps

Once you setup Aurora you can add many apps for different needs such as media, torrenting, AI, note taking, image hosting.


# Rules

- SSO should be used in most situations; if a new app doesn't support it then notify the user that its not protected.
- VPN and privacy for network traffic egressing should be available
- Core apps should be pinned to stable version and not use `:latest` tags or versions
- Dependencies for core should live in core, if someone adds an additional tool this should not degrade or upgrade the core of Aurora's functionality
- Docker is your friend, use it.
- Simple notifications or messages should use Aurora's email address (normally admin@aurora.local), it just makes life easier.
- A limit of three alternatives for a tool can be on the marketplace

e.g. if there is 3 email clients (Roundcube, Rainloop, simplemail) then no more can be added.

# License

MIT

# Human Noise

_This is five phrases that a human must type out that can be random or quoted from somewhere_

- I would have been your daddy
- Tobey Maguire is the best spider man
- Tuna and peanut butter is an interesting combination
- Take two sticks and snap them in half
- Absorbing fabrics aren't always the best for polishing cars