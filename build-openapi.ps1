# Build OpenAPI and generate TypeScript Angular client
mvn -Popenapi-gen package

# Remove old generated files
Remove-Item -Path "src/main/webapp/src/app/rest/*.ts" -Force -Recurse
Remove-Item -Path "src/main/webapp/src/app/rest/api" -Force -Recurse
Remove-Item -Path "src/main/webapp/src/app/rest/model" -Force -Recurse

# Copy new generated files
Copy-Item -Path "target/generated-typescript-angular/*.ts" -Destination "src/main/webapp/src/app/rest/" -Force -Recurse
Copy-Item -Path "target/generated-typescript-angular/api" -Destination "src/main/webapp/src/app/rest/" -Force -Recurse
Copy-Item -Path "target/generated-typescript-angular/model" -Destination "src/main/webapp/src/app/rest/" -Force -Recurse
