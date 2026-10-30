FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive

# Development environment (JDK) + libs required by headless Chrome
RUN apt-get update && apt-get install -y --no-install-recommends \
        openjdk-21-jdk-headless wget unzip ca-certificates fonts-liberation \
        libnss3 libatk1.0-0t64 libatk-bridge2.0-0t64 libcups2t64 libdrm2 libxkbcommon0 \
        libxcomposite1 libxdamage1 libxfixes3 libxrandr2 libgbm1 libasound2t64 \
        libpango-1.0-0 libcairo2 libx11-6 libxcb1 libxext6 libglib2.0-0t64 libdbus-1-3 libexpat1 \
    && rm -rf /var/lib/apt/lists/*

# Chrome + ChromeDriver version (matches your lb4 downloads)
ARG CHROME_VERSION=156.0.8078.73
ARG SELENIUM_VERSION=4.51.0

# Chrome (headless shell) + matching ChromeDriver
RUN wget -q https://storage.googleapis.com/chrome-for-testing-public/${CHROME_VERSION}/linux64/chrome-headless-shell-linux64.zip -O /tmp/chrome.zip \
 && wget -q https://storage.googleapis.com/chrome-for-testing-public/${CHROME_VERSION}/linux64/chromedriver-linux64.zip -O /tmp/driver.zip \
 && unzip -q /tmp/chrome.zip -d /opt && unzip -q /tmp/driver.zip -d /opt \
 && chmod +x /opt/chrome-headless-shell-linux64/chrome-headless-shell /opt/chromedriver-linux64/chromedriver \
 && rm /tmp/chrome.zip /tmp/driver.zip

# Selenium Java (flatten all runtime jars into one folder, drop *-sources.jar)
RUN wget -q https://github.com/SeleniumHQ/selenium/releases/download/selenium-${SELENIUM_VERSION}/selenium-java-${SELENIUM_VERSION}.zip -O /tmp/selenium.zip \
 && mkdir -p /tmp/sel /opt/selenium/lib \
 && unzip -q /tmp/selenium.zip -d /tmp/sel \
 && find /tmp/sel -name '*.jar' ! -name '*-sources.jar' -exec cp {} /opt/selenium/lib/ \; \
 && rm -rf /tmp/sel /tmp/selenium.zip

ENV CHROME_BIN=/opt/chrome-headless-shell-linux64/chrome-headless-shell
ENV CHROMEDRIVER_PATH=/opt/chromedriver-linux64/chromedriver

# Selenium automation code + summarizer + REST API
WORKDIR /app
COPY src/ /app/src/
RUN mkdir -p /app/out && javac -cp "/opt/selenium/lib/*" -d /app/out /app/src/*.java

EXPOSE 7000

# API starts automatically when the container runs
CMD ["java", "-cp", "/app/out:/opt/selenium/lib/*", "App"]
