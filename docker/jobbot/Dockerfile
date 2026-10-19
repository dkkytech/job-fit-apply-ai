# JobBot: the Hermes agent gateway for @DkkyAIJobBot, pinned by digest.
FROM nousresearch/hermes-agent:v2026.9.24@sha256:fca358f12efd65bfaaca05884166f15c0e2788375ca30d77061ac1ebc96452b7

# Config, persona, workflows and plugins are baked in root-owned, then seeded into /opt/data
# on every start (cont-init 03 runs after the image's own 01 setup and before the gateway).
COPY --chown=root:root config.yaml.tmpl SOUL.md seed.py /opt/jobbot/
COPY --chown=root:root skills/ /opt/jobbot/skills/
COPY --chown=root:root plugins/ /opt/jobbot/plugins/
RUN rm -rf /opt/jobbot/plugins/*/tests \
    && printf '#!/command/with-contenv sh\nexec /opt/hermes/.venv/bin/python /opt/jobbot/seed.py\n' \
        > /etc/cont-init.d/03-jobbot-seed \
    && chmod +x /etc/cont-init.d/03-jobbot-seed

HEALTHCHECK --interval=60s --timeout=10s --start-period=90s --retries=3 \
    CMD ["/opt/hermes/.venv/bin/python", "-c", "import json,sys; s=json.load(open('/opt/data/gateway_state.json')); sys.exit(0 if s.get('gateway_state', s.get('state'))=='running' else 1)"]

CMD ["gateway", "run"]
