# Lab Assignment 4 - Selenium News API (CSC418 DevOps for Cloud Computing)

- Registration No: SP23-BAI-023
- News source: NPR (formula gave #4 Pakistan Today, which is behind a Cloudflare challenge; switched to NPR with the teacher's permission)
- Language: Java (Selenium 4.51.0, headless Chrome + ChromeDriver)
- API: `GET /get?keyword={keyword}` on port 7000

## Run
```
docker compose up --build -d
curl "http://localhost:7000/get?keyword=imran"
```
Open `http://<server-ip>:7000/` to see the registration number page.

## Docker Hub image
`ramish52843/sp23-bai-023-news-api:1.0`

## Files
- `Dockerfile` - JDK + Chrome headless shell + ChromeDriver + Selenium jars, compiles and auto-starts the API
- `docker-compose.yml` - builds and runs the container
- `src/App.java` - REST API server (port 7000)
- `src/NewsScraper.java` - Selenium automation (search, first result, article text)
- `src/Summarizer.java` - extractive summarization module

## Note
The scraper first uses NPR's own search page. If the site blocks it, it searches the same site
(`site:npr.org keyword`) through DuckDuckGo/Bing with Selenium and opens the first result.
