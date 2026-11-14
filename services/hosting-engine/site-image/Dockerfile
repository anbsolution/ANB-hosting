FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/nginx.conf
COPY site.conf /etc/nginx/conf.d/default.conf
COPY index.html /usr/share/nginx/html/index.html
USER 101:101
EXPOSE 8080
