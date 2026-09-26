
```bash
mvn -Popenapi-gen package
rm -rf src/main/webapp/src/app/rest/*.ts
rm -rf src/main/webapp/src/app/rest/api
rm -rf src/main/webapp/src/app/rest/model
cp -rf target/generated-typescript-angular/*.ts src/main/webapp/src/app/rest/
cp -rf target/generated-typescript-angular/api src/main/webapp/src/app/rest/
cp -rf target/generated-typescript-angular/model src/main/webapp/src/app/rest/

```