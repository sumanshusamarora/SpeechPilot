FROM node:20-alpine

WORKDIR /workspace/src/web

COPY src/contracts/typescript /workspace/src/contracts/typescript
COPY src/web/package.json /workspace/src/web/package.json
RUN npm install

COPY src/web /workspace/src/web

EXPOSE 3000

CMD ["npm", "run", "dev", "--", "--hostname", "0.0.0.0", "--port", "3000"]