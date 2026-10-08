# University Portal


## Как запустить

git clone https://github.com/Zuika1166/UniversityPortal.git
cd UniversityPortal
mvn spring-boot:run

После запуска сайт будет доступен по адресу http://localhost:8080.

## API-ключ

API ключ можно получить тут https://api-university.vercel.app/

В PowerShell задать ключ:

$env:UNIVERSITY_API_KEY = "university-api-key-2025"
mvn spring-boot:run


## Проверка

mvn test
