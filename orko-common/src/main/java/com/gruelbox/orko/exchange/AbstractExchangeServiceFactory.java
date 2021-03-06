/**
 * Orko
 * Copyright © 2018-2019 Graham Crockford
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.gruelbox.orko.exchange;

import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import com.gruelbox.orko.OrkoConfiguration;

abstract class AbstractExchangeServiceFactory<T> {

  private final OrkoConfiguration configuration;

  AbstractExchangeServiceFactory(OrkoConfiguration configuration) {
    this.configuration = configuration;
  }

  public T getForExchange(String exchange) {
    Map<String, ExchangeConfiguration> exchangeConfig = configuration.getExchanges();
    if (exchangeConfig == null) {
      return getPaperFactory().getForExchange(exchange);
    }
    final ExchangeConfiguration exchangeConfiguration = configuration.getExchanges().get(exchange);
    if (exchangeConfiguration == null || StringUtils.isEmpty(exchangeConfiguration.getApiKey())) {
      return getPaperFactory().getForExchange(exchange);
    }
    return getRealFactory().getForExchange(exchange);
  }

  protected abstract ExchangeServiceFactory<T> getRealFactory();

  protected abstract ExchangeServiceFactory<T> getPaperFactory();
}